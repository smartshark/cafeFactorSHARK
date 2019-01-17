package cafeFactorSHARK;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.mongodb.morphia.Datastore;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import common.MongoAdapter;
import common.cafe.CafeFactorConfigurationHandler;
import common.cafe.CafeFactorParameter;
import de.ugoe.cs.smartshark.model.CFAState;
import de.ugoe.cs.smartshark.model.Commit;
import de.ugoe.cs.smartshark.model.VCSSystem;

/**
 * @author Philip Makedonski
 */

public class CafeFactorApp {
	protected MongoAdapter adapter;
	protected Datastore targetstore;
	protected VCSSystem vcs;
	protected static Logger logger = (Logger) LoggerFactory.getLogger(CafeFactorApp.class.getSimpleName());

	public static void main(String[] args) {
		//load configuration -> override parameters
		if (args.length == 1) {
			args = CafeFactorConfigurationHandler.getInstance().loadConfiguration("properties/sample");
		}
		
		CafeFactorParameter.getInstance().init(args);
		CafeFactorConfigurationHandler.getInstance().setLogLevel(CafeFactorParameter.getInstance().getDebugLevel());
		
		CafeFactorApp app = new CafeFactorApp();
		
		if (CafeFactorParameter.getInstance().getCommit() == null) {
			app.processRepository();
		} else {
			app.processCommit();
		}
	}
	
	public CafeFactorApp() {
		//init
		init();
	}

	void init() {
		adapter = new MongoAdapter(CafeFactorParameter.getInstance());
		adapter.setPluginName("cafeFactorSHARK");
		adapter.setRecordProgress(CafeFactorParameter.getInstance().isRecordProgress());
		targetstore = adapter.getTargetstore();
		adapter.setVcs(CafeFactorParameter.getInstance().getUrl());
		if (adapter.getVcs()==null) {
			logger.error("No VCS information found for "+CafeFactorParameter.getInstance().getUrl());
			System.exit(1);
		}
	}

	private void addRemovedWeights(List<CFAState> pStates) {
		int i = 0;
		int size = pStates.size();

		for (CFAState pState : pStates) {
			i++;
			logger.info("Adding factors: "+i+"/"+size);
			
			//reset
			pState.getFactors().clear();
			
			addFactor(pState, "default", 1.0);

			Commit commit = adapter.getCommit(pState.getEntityId());
			//trivial pilot
			//TODO: use labels instead
			String message = commit.getMessage();
			boolean matches = message.contains("fix");

			addFactor(pState, "fix", matches ? 1.0 : 0.0);

			addFactor(pState, "adjustedszz_bugfix", Boolean.parseBoolean(commit.getLabels().get("adjustedszz_bugfix")) ? 1.0 : 0.0);
			addFactor(pState, "refactoring_codebased", Boolean.parseBoolean(commit.getLabels().get("refactoring_codebased")) ? 1.0 : 0.0);
			
			targetstore.save(pState);
		}
	}

	private void addFactor(CFAState state, String name, double value) {
		state.getFactors().put(name, new LinkedHashMap<>());
		state.getFactors().get(name).put("rw", value);
	}

	private void shareRemovedWeights(List<CFAState> pStates) {
		int i = 0;
		int size = pStates.size();
		int c = 0;
		//TODO: add different strategies
		for (CFAState pState : pStates) {
			i++;
			logger.info("Sharing removed weights: "+i+"/"+size);
			logger.info("  Children: "+pState.getChildrenIds().size());
			for (ObjectId childId : pState.getChildrenIds()) {
				CFAState cState = targetstore.get(CFAState.class, childId);
				c++;
				//reset
				cState.getFactors().clear();

				for (String f : pState.getFactors().keySet()) {
					//TODO: different strategies here?
					addFactor(cState, f, pState.getFactors().get(f).get("rw"));
				}
				logger.info("    "+c+" Factors "+cState.getFactors());
				targetstore.save(cState);
			}
		}
	}
	
	private void calculateAverageWeights(List<CFAState> pStates) {
		int size = pStates.size();
		int i = 0;
		//averages
		//TODO: split and calculate per factor
		for (CFAState pState : pStates) {
			i++;
			logger.info("Adding average weights: "+i+"/"+size);
			int fixes = pState.getCausesIds().size();
			if (fixes == 0) {
				fixes = 1;
			}
			for (String f : pState.getFactors().keySet()) {
				double value = 0;
				Map<String, Double> sFactor = pState.getFactors().get(f);
				if (sFactor.containsKey("tw")) {
					value = sFactor.get("tw");
				}
				value = value / fixes;
				pState.getFactors().get(f).put("aw",value);
			}
		}
		targetstore.save(pStates);
	}

	private void calculateTotalWeights(List<CFAState> pStates) {
		int size = pStates.size();
		int i = 0;
		//totals
		//TODO: split and calculate per factor
		for (CFAState pState : pStates) {
			//TODO: investigate why this can happen
			if (pState.getFactors().isEmpty()) {
				continue;
			}
			i++;
			logger.info("Adding total weights: "+i+"/"+size);
			int causes = pState.getFixesIds().size();
			if (causes == 0) {
				causes = 1;
			}
			for (ObjectId causeId : pState.getFixesIds()) {
				CFAState cause = targetstore.get(CFAState.class, causeId);
				for (String f : cause.getFactors().keySet()) {
					double value = 0;
					Map<String, Double> cFactor = cause.getFactors().get(f);
					Map<String, Double> sFactor = pState.getFactors().get(f);
					if (cFactor.containsKey("tw")) {
						value = cFactor.get("tw");
					}
					value = value + sFactor.get("rw") *	(1.0/causes);
					cause.getFactors().get(f).put("tw",value);
				}
				targetstore.save(cause);
			}
		}
	}

	private void resetTotalAndAverageWeights(List<CFAState> pStates) {
		int size = pStates.size();
		int i = 0;
		//reset total and average 
		//TODO: split / parameterise ? 
		for (CFAState pState : pStates) {
			i++;
			logger.info("Resetting weights: "+i+"/"+size);
			for (String f : pState.getFactors().keySet()) {
				double value = 0;
				pState.getFactors().get(f).put("tw",value);
				pState.getFactors().get(f).put("aw",value);
			}
		}
		targetstore.save(pStates);
	}
	
	public void processRepository() {
		//TODO: add sharing / inherited strategies
		//TODO: visualise / compare with decent
		//TODO: add carried weights?
		// -> sum over total-rw
		//   -> carried average is cw/remaining future fixes
		//   -> need to record count of future fixes as an attribute per factor 
		//		-> only if rw > 0? 
		
		//TODO: better implementation of project specific manner
		// -> current version may be inefficient
		
		logger.info("PROJECT LEVEL");
		List<Commit> commits = adapter.getCommits();
		List<ObjectId> ids = commits.stream().map(e->e.getId()).collect(Collectors.toList());

		List<CFAState> pStates = targetstore.find(CFAState.class)
				.field("type").equal("project")
				.field("entity_id").in(ids).asList();
		addRemovedWeights(pStates);
		
		resetTotalAndAverageWeights(pStates);
		calculateTotalWeights(pStates);
		calculateAverageWeights(pStates);

		logger.info("FILE LEVEL");
		shareRemovedWeights(pStates);

		List<ObjectId> pIds = pStates.stream().map(e->e.getId()).collect(Collectors.toList());

		List<CFAState> fStates = targetstore.find(CFAState.class)
				.field("type").equal("file")
				.field("parent_id").in(pIds).asList();
		resetTotalAndAverageWeights(fStates);
		calculateTotalWeights(fStates);
		calculateAverageWeights(fStates);

		logger.info("LOGICAL LEVEL");
		shareRemovedWeights(fStates);

		List<ObjectId> fIds = fStates.stream().map(e->e.getId()).collect(Collectors.toList());

		//TODO: support other types?
		List<CFAState> lStates = targetstore.find(CFAState.class)
				.field("type").equal("method")
				.field("parent_id").in(fIds).asList();

		resetTotalAndAverageWeights(lStates);
		calculateTotalWeights(lStates);
		calculateAverageWeights(lStates);
		
		//TODO: add recursive processing?
		
		//TODO: add inheriting all weights for baseline calculation
	}
	
	public void processCommit() {
		processCommit(CafeFactorParameter.getInstance().getCommit());
	}

	public void processCommit(String hash) {
        processCommit(adapter.getCommit(hash));
	}
	
	public void processCommit(Commit commit) {
		//TODO: add sharing / inherited strategies

		List<CFAState> pStates = targetstore.find(CFAState.class)
				.field("type").equal("project")
				.field("entity_id").equal(commit.getId()).asList();

		addRemovedWeights(pStates);
		
		resetTotalAndAverageWeights(pStates);
		calculateTotalWeights(pStates);
		calculateAverageWeights(pStates);

		//file states
		shareRemovedWeights(pStates);

		List<CFAState> fStates = targetstore.find(CFAState.class)
				.field("type").equal("file")
				.field("parent_id").equal(pStates.get(0).getId()).asList();
		
		resetTotalAndAverageWeights(fStates);
		calculateTotalWeights(fStates);
		calculateAverageWeights(fStates);

		//logical states
		shareRemovedWeights(fStates);

		List<ObjectId> fIds = fStates.stream().map(e->e.getId()).collect(Collectors.toList());

		//TODO: support other types?
		List<CFAState> lStates = targetstore.find(CFAState.class)
				.field("type").equal("method")
				.field("parent_id").in(fIds).asList();

		resetTotalAndAverageWeights(lStates);
		calculateTotalWeights(lStates);
		calculateAverageWeights(lStates);
		
		//TODO: add inheriting all weights for baseline calculation
	}
	
}
