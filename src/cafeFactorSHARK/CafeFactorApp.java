package cafeFactorSHARK;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.mongodb.morphia.Datastore;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import common.DatabaseHandler;
import common.cafe.CafeFactorConfigurationHandler;
import common.cafe.CafeFactorParameter;
import de.ugoe.cs.smartshark.model.CFAFactor;
import de.ugoe.cs.smartshark.model.CFAState;
import de.ugoe.cs.smartshark.model.CodeEntityState;
import de.ugoe.cs.smartshark.model.Commit;
import de.ugoe.cs.smartshark.model.File;
import de.ugoe.cs.smartshark.model.FileAction;
import de.ugoe.cs.smartshark.model.VCSSystem;

/**
 * @author Philip Makedonski
 */

public class CafeFactorApp {
	protected Datastore datastore;
	protected Datastore targetstore;
	private HashMap<String, Commit> commitCache = new HashMap<>();
	private HashMap<ObjectId, Commit> commitIdCache = new HashMap<>();
	private HashMap<ObjectId, CFAState> cfaCache = new HashMap<>();
	private HashMap<ObjectId, CFAState> cfaEntityCache = new HashMap<>();
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
		//TODO: make optional or merge
//		targetstore = DatabaseHandler.createDatastore("localhost", 27017, "cfashark");
		datastore = DatabaseHandler.createDatastore(CafeFactorParameter.getInstance());
		targetstore = datastore;
		vcs = datastore.find(VCSSystem.class)
    		.field("url").equal(CafeFactorParameter.getInstance().getUrl()).get();
	}

	private void addRemovedWeights(List<CFAState> pStates) {
		int i = 0;
		int size = pStates.size();

		for (CFAState pState : pStates) {
			i++;
			logger.info("Adding factors: "+i+"/"+size);
			
			//reset
			//fetch existing otherwise create new instead of clearing?
			for (ObjectId fid : pState.getFactors().values()) {
				targetstore.delete(targetstore.get(CFAFactor.class, fid));
			}
			pState.getFactors().clear();
			
			addFactor(pState, "default", 1.0);

			Commit commit = getCommit(pState.getEntityId());
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
		CFAFactor factor = new CFAFactor();
		factor.setName(name);
		factor.getValues().put("rw", value);
		targetstore.save(factor);
		state.getFactors().put(name,factor.getId());
	}

	private void shareRemovedWeights(List<CFAState> pStates) {
		int i = 0;
		int size = pStates.size();

		//TODO: add different strategies
		for (CFAState pState : pStates) {
			i++;
			logger.info("Sharing removed weights: "+i+"/"+size);
			for (ObjectId childId : pState.getChildrenIds()) {
				CFAState cState = targetstore.get(CFAState.class, childId);

				//reset
				//fetch existing otherwise create new instead of clearing?
				for (ObjectId fid : cState.getFactors().values()) {
					targetstore.delete(targetstore.get(CFAFactor.class, fid));
				}
				cState.getFactors().clear();

				for (String f : pState.getFactors().keySet()) {
					CFAFactor sFactor = targetstore.get(CFAFactor.class, pState.getFactors().get(f));
					//TODO: different strategies here?
					addFactor(cState, sFactor.getName(), sFactor.getValues().get("rw"));
				}
				logger.info("        Factors"+cState.getFactors().size());
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
				CFAFactor sFactor = targetstore.get(CFAFactor.class, pState.getFactors().get(f));
				if (sFactor.getValues().containsKey("tw")) {
					value = sFactor.getValues().get("tw");
				}
				value = value / fixes;
				sFactor.getValues().put("aw",value);
				targetstore.save(sFactor);
			}
		}
	}

	private void calculateTotalWeights(List<CFAState> pStates) {
		int size = pStates.size();
		int i = 0;
		//totals
		//TODO: split and calculate per factor
		for (CFAState pState : pStates) {
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
					CFAFactor cFactor = targetstore.get(CFAFactor.class, cause.getFactors().get(f));
					CFAFactor sFactor = targetstore.get(CFAFactor.class, pState.getFactors().get(f));
					if (cFactor.getValues().containsKey("tw")) {
						value = cFactor.getValues().get("tw");
					}
					value = value + sFactor.getValues().get("rw") * (1.0/causes);
					cFactor.getValues().put("tw",value);
					targetstore.save(cFactor);
				}
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
				CFAFactor factor = targetstore.get(CFAFactor.class, pState.getFactors().get(f));
				factor.getValues().put("tw",value);
				factor.getValues().put("aw",value);
				targetstore.save(factor);
			}
		}
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
		List<Commit> commits = datastore.find(Commit.class)
				.field("vcs_system_id").equal(vcs.getId()).asList();
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
        processCommit(getCommit(hash));
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

	Commit getCommit(String hash) {
		if (!commitCache.containsKey(hash)) {
			Commit commit = datastore.find(Commit.class)
					.field("vcs_system_id").equal(vcs.getId())
					.field("revision_hash").equal(hash).get();
			commitCache.put(hash, commit);
			commitIdCache.put(commit.getId(), commit);
		}
		return commitCache.get(hash);
	}
	
	Commit getCommit(ObjectId id) {
		if (!commitIdCache.containsKey(id)) {
			Commit commit = datastore.get(Commit.class, id);
			commitCache.put(commit.getRevisionHash(), commit);
			commitIdCache.put(id, commit);
		}
		return commitIdCache.get(id);
	}

	CFAState getCFAState(ObjectId id) {
		if (!cfaCache.containsKey(id)) {
			CFAState state = targetstore.get(CFAState.class, id);
			if (state == null) {
				return state;
			}
			cfaCache.put(id, state);
			cfaEntityCache.put(state.getEntityId(), state);
		}
		return cfaCache.get(id);
	}

	CFAState getCFAStateForEntity(ObjectId id) {
		if (!cfaEntityCache.containsKey(id)) {
			CFAState state = targetstore.find(CFAState.class)
				.field("entity_id").equal(id)
				.get();
			if (state == null) {
				return state;
			}
			cfaEntityCache.put(id, state);
			cfaCache.put(state.getId(), state);
		}
		return cfaEntityCache.get(id);
	}
	
}
