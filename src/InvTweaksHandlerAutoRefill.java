import invtweaks.InvTweaksConst;
import invtweaks.InvTweaksItemTree;
import invtweaks.InvTweaksItemTreeItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;


import net.minecraft.client.Minecraft;

/**
 * Handles the auto-refilling of the hotbar.
 * 
 * @author Jimeo Wan
 *
 */
public class InvTweaksHandlerAutoRefill extends InvTweaksObfuscation {
    
    private static final Logger log = Logger.getLogger("InvTweaks");

    private InvTweaksConfig config = null;
    
    public InvTweaksHandlerAutoRefill(Minecraft mc, InvTweaksConfig config) {
		super(mc);
		setConfig(config);
	}
    
    public void setConfig(InvTweaksConfig config) {
    	this.config = config;
    }
    
	/**
     * Auto-refill
	 * @throws Exception 
     */
	public void autoRefillSlot(int slot, int wantedId, int wantedDamage) throws Exception {

		InvTweaksContainerSectionManager container = new InvTweaksContainerSectionManager(
		        mc, InvTweaksContainerSection.INVENTORY);
		rj candidateStack, replacementStack = null;
		int replacementStackSlot = -1;
		
		List<InvTweaksConfigSortingRule> matchingRules = new ArrayList<InvTweaksConfigSortingRule>();
		List<InvTweaksConfigSortingRule> rules = config.getRules();
		InvTweaksItemTree tree = config.getTree();
		
		// Check that the item is in the tree
        if (!tree.isItemUnknown(wantedId, wantedDamage)) {
            
            //// Search replacement
            
    		List<InvTweaksItemTreeItem> items = tree.getItems(wantedId, wantedDamage);
    
    		// Find rules that match the slot
    		for (InvTweaksItemTreeItem item : items) {
    			// Since we search a matching item using rules,
    		    // create a fake one that matches the exact item first
    			matchingRules.add(new InvTweaksConfigSortingRule(
    					tree, "D"+(slot-27), item.getName(),
    					InvTweaksConst.INVENTORY_SIZE, InvTweaksConst.INVENTORY_ROW_SIZE));
    		}
    		for (InvTweaksConfigSortingRule rule : rules) {
    			if (rule.getType() == InvTweaksConfigSortingRuleType.SLOT 
    			        || rule.getType() == InvTweaksConfigSortingRuleType.COLUMN) {
    				for (int preferredSlot : rule.getPreferredSlots()) {
    					if (slot == preferredSlot) {
    						matchingRules.add(rule);
    						break;
    					}
    				}
    			}
    		}
    
    		// Look only for a matching stack
    		// First, look for the same item,
    		// else one that matches the slot's rules
    		for (InvTweaksConfigSortingRule rule : matchingRules) {
    			for (int i = 0; i < InvTweaksConst.INVENTORY_SIZE; i++) {
    				candidateStack = container.getItemStack(i);
    				if (candidateStack != null) {
    					List<InvTweaksItemTreeItem> candidateItems = tree.getItems(
    							getItemID(candidateStack),
    							getItemDamage(candidateStack));
    					if (tree.matches(candidateItems, rule.getKeyword())) {
    						// Choose stack of lowest size and (in case of tools) highest damage
    						if (replacementStack == null || 
    								getStackSize(replacementStack) > getStackSize(candidateStack) ||
    								(getStackSize(replacementStack) == getStackSize(candidateStack) &&
    										getMaxStackSize(replacementStack) == 1 &&
    										getItemDamage(replacementStack) < getItemDamage(candidateStack))) {
    							replacementStack = candidateStack;
    							replacementStackSlot = i;
    						}
    					}
    				}
    			}
    			if (replacementStack != null) {
    				break;
    			}
    		}
        }
        
        // If item is unknown, look for exact same item
        else {
            for (int i = 0; i < InvTweaksConst.INVENTORY_SIZE; i++) {
                candidateStack = container.getItemStack(i);
                if (candidateStack != null && 
                        getItemID(candidateStack) == wantedId &&
                        getItemDamage(candidateStack) == wantedDamage) {
                    replacementStack = candidateStack;
                    replacementStackSlot = i;
                    break;
                }
            }
        }
		
		//// Proceed to replacement
	
		if (replacementStack != null) {
			
			log.info("Automatic stack replacement.");
			
		    /*
		     * This allows to have a short feedback 
		     * that the stack/tool is empty/broken.
		     */
			new Thread(new Runnable() {

				private InvTweaksContainerSectionManager containerMgr;
				private int targetedSlot;
				private int i, expectedItemId;
				
				public Runnable init(Minecraft mc,
						int i, int currentItem) throws Exception {
					this.containerMgr = new InvTweaksContainerSectionManager(
					        mc, InvTweaksContainerSection.INVENTORY);
					this.targetedSlot = currentItem;
					this.expectedItemId = getItemID(
					        containerMgr.getItemStack(i));
					this.i = i;
					return this;
				}
				
				public void run() {
					
					// Wait for the server to confirm that the
					// slot is now empty
					int pollingTime = 0;
					setHasInventoryChanged(false);
					while(getThePlayer() != null && !hasInventoryChanged()
							&& pollingTime < InvTweaksConst.POLLING_TIMEOUT) {
						trySleep(InvTweaksConst.POLLING_DELAY);
					}
					if (getThePlayer() == null) {
					    return; // Game closed
					}
					if (pollingTime < InvTweaksConst.AUTO_REFILL_DELAY)
						trySleep(InvTweaksConst.AUTO_REFILL_DELAY - pollingTime);
					if (pollingTime >= InvTweaksConst.POLLING_TIMEOUT)
						log.warning("Autoreplace timout");
					
					// In POLLING_DELAY ms, things might have changed
					try {
						rj stack = containerMgr.getItemStack(i);
						if (stack != null && getItemID(stack) == expectedItemId) {
							if (containerMgr.move(i, targetedSlot)) {
								if (!config.getProperty(InvTweaksConfig.PROP_ENABLE_SOUNDS).equals(InvTweaksConfig.VALUE_FALSE)) {
								    playSound("mob.chickenplop", 1.4F, 0.5F);
								}
								// If item are swapped (like for mushroom soups),
								// put the item back in the inventory if it is in the hotbar
								if (containerMgr.getItemStack(i) != null && i >= 27) {
									for (int j = 0; j < InvTweaksConst.INVENTORY_SIZE; j++) {
										if (containerMgr.getItemStack(j) == null) {
										    containerMgr.move(i, j);
											break;
										}
									}
								}
							}
							else {
								log.warning("Failed to move stack for autoreplace, despite of prior tests.");
							}
						}
					}
					catch (NullPointerException e) {
						// Nothing: Due to multithreading + 
						// unsafe accesses, NPE may (very rarely) occur (?).
					} catch (TimeoutException e) {
						log.severe("Failed to trigger autoreplace: "+e.getMessage());
					}
					
				}
				
			}.init(mc, replacementStackSlot, slot)).start();
			
		}
    }
	
	private static void trySleep(int delay) {
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) {
			// Do nothing
		}
    }

}