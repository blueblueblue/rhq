/*
 * RHQ Management Platform
 * Copyright (C) 2005-2008 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.gui.navigation.resource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.rhq.core.domain.auth.Subject;
import org.rhq.core.domain.measurement.AvailabilityType;
import org.rhq.core.domain.resource.Agent;
import org.rhq.core.domain.resource.Resource;
import org.rhq.core.domain.resource.ResourceSubCategory;
import org.rhq.core.domain.resource.ResourceType;
import org.rhq.core.domain.resource.composite.LockedResource;
import org.rhq.core.domain.resource.group.composite.AutoGroupComposite;
import org.rhq.core.domain.util.PageControl;
import org.rhq.core.gui.util.FacesContextUtility;
import org.rhq.enterprise.gui.util.EnterpriseFacesContextUtility;
import org.rhq.enterprise.server.core.AgentManagerLocal;
import org.rhq.enterprise.server.resource.ResourceManagerLocal;
import org.rhq.enterprise.server.util.HibernatePerformanceMonitor;
import org.rhq.enterprise.server.util.LookupUtil;

/**
 * Backing bean for the left navigation tree for resources
 *
 * @author Greg Hinkle
 */
public class ResourceTreeModelUIBean {
    private static final Log log = LogFactory.getLog(ResourceTreeModelUIBean.class);

    private List<ResourceTreeNode> roots = new ArrayList<ResourceTreeNode>();
    private ResourceTreeNode rootNode = null;

    private ResourceManagerLocal resourceManager = LookupUtil.getResourceManager();
    private AgentManagerLocal agentManager = LookupUtil.getAgentManager();

    private String nodeTitle;

    private void loadTree() {
        int searchId;
        Resource currentResource = EnterpriseFacesContextUtility.getResourceIfExists();
        if (currentResource == null) {
            searchId = Integer.parseInt(FacesContextUtility.getOptionalRequestParameter("parent"));
        } else {
            searchId = currentResource.getId();
        }

        Subject user = EnterpriseFacesContextUtility.getSubject();

        long start = System.currentTimeMillis();
        long monitorId = HibernatePerformanceMonitor.get().start();
        Resource rootResource = resourceManager.getRootResourceForResource(searchId);
        long end = System.currentTimeMillis();
        HibernatePerformanceMonitor.get().stop(monitorId, "ResourceTree root resource");
        log.debug("Found root resource in " + (end - start));

        Agent agent = agentManager.getAgentByResourceId(rootResource.getId());

        start = System.currentTimeMillis();
        monitorId = HibernatePerformanceMonitor.get().start();
        List<Resource> resources = resourceManager.findResourcesByAgent(user, agent.getId(), PageControl
            .getUnlimitedInstance());
        end = System.currentTimeMillis();
        HibernatePerformanceMonitor.get().stop(monitorId, "ResourceTree agent resource");
        log.debug("Loaded " + resources.size() + " raw resources in " + (end - start));


        start = System.currentTimeMillis();
        monitorId = HibernatePerformanceMonitor.get().start();
        rootNode = load(rootResource.getId(), resources, false);
        end = System.currentTimeMillis();
        HibernatePerformanceMonitor.get().stop(monitorId, "ResourceTree tree construction");
        log.debug("Constructed tree in " + (end - start));
    }

    public static ResourceTreeNode load(int rootId, List<Resource> resources, boolean alwaysGroup) {
        Resource found = null;
        for (Resource res : resources) {
            if (res.getId() == rootId) {
                found = res;
            }
        }
        ResourceTreeNode root = new ResourceTreeNode(found);
        long start = System.currentTimeMillis();
        load(root, alwaysGroup);
        return root;
    }

    
    public static void load(ResourceTreeNode parentNode, boolean alwaysGroup) {

        if (parentNode.getData() instanceof Resource) {
            Resource parentResource = (Resource) parentNode.getData();

            Map<Object, List<Resource>> children = new HashMap<Object, List<Resource>>();
            for (Resource res : parentResource.getChildResources()) {
                if (res.getResourceType().getSubCategory() != null) {
                    // These are children that have subcategories
                    // Split them by if they are a sub-sub category or just a category
                    if (res.getResourceType().getSubCategory().getParentSubCategory() == null) {
                        if (children.containsKey(res.getResourceType().getSubCategory())) {
                            children.get(res.getResourceType().getSubCategory()).add(res);
                        } else {
                            ArrayList<Resource> list = new ArrayList<Resource>();
                            list.add(res);
                            children.put(res.getResourceType().getSubCategory(), list);
                        }
                    } else if (res.getResourceType().getSubCategory().getParentSubCategory() != null) {
                        if (children.containsKey(res.getResourceType().getSubCategory().getParentSubCategory())) {
                            children.get(res.getResourceType().getSubCategory().getParentSubCategory()).add(res);
                        } else {
                            ArrayList<Resource> list = new ArrayList<Resource>();
                            list.add(res);
                            children.put(res.getResourceType().getSubCategory().getParentSubCategory(), list);
                        }
                    }
                } else {
                    // These are children without categories of the parent resource
                    // - Add them into groupings by their resource type
                    if (children.containsKey(res.getResourceType())) {
                        children.get(res.getResourceType()).add(res);
                    } else {
                        ArrayList<Resource> list = new ArrayList<Resource>();
                        list.add(res);
                        children.put(res.getResourceType(), list);
                    }
                }

            }

            Set<String> dupResourceTypeNames = getDuplicateResourceTypeNames(children, alwaysGroup);

            for (Object rsc : children.keySet()) {
                if (rsc != null
                    && (rsc instanceof ResourceSubCategory || children.get(rsc).size() > 1 || (alwaysGroup && children
                        .get(rsc).size() == 1))) {
                    double avail = 0;
                    List<Resource> entries = children.get(rsc);
                    for (Resource res : entries) {
                        avail += res.getCurrentAvailability().getAvailabilityType() == AvailabilityType.UP ? 1 : 0;
                    }
                    avail = avail / entries.size();

                    AutoGroupComposite agc = null;
                    if (rsc instanceof ResourceSubCategory) {
                        agc = new AutoGroupComposite(avail, parentResource, (ResourceSubCategory) rsc, entries.size());
                    } else if (rsc instanceof ResourceType) {
                        boolean isDupResourceTypeName = dupResourceTypeNames.contains(((ResourceType)rsc).getName());
                        agc = new AutoGroupComposite(avail, parentResource, (ResourceType) rsc, entries.size(),
                                isDupResourceTypeName);
                    }
                    ResourceTreeNode node = new ResourceTreeNode(agc);
                    load(node, alwaysGroup);

                    if (!recursivelyLocked(node)) {
                        parentNode.getChildren().add(node);
                    }
                } else {
                    List<Resource> entries = children.get(rsc);
                    for (Resource res : entries) {
                        ResourceTreeNode node = new ResourceTreeNode(res);

                        load(node, alwaysGroup);
                        if (!recursivelyLocked(node)) {
                            parentNode.getChildren().add(node);
                        }
                    }
                }

            }
        } else {
            // #####################################################################################

            AutoGroupComposite compositeParent = (AutoGroupComposite) parentNode.getData();

            Map<Object, List<Resource>> children = new HashMap<Object, List<Resource>>();
            for (Resource res : compositeParent.getParentResource().getChildResources()) {
                if (compositeParent.getSubcategory() != null) {
                    // parent is a sub category
                    if (res.getResourceType().getSubCategory() != null
                        && compositeParent.getSubcategory().equals(
                            res.getResourceType().getSubCategory().getParentSubCategory())
                        && compositeParent.getParentResource().equals(res.getParentResource())) {

                        // A subSubCategory in a subcategory
                        if (children.containsKey(res.getResourceType().getSubCategory())) {
                            children.get(res.getResourceType().getSubCategory()).add(res);
                        } else {
                            ArrayList<Resource> list = new ArrayList<Resource>();
                            list.add(res);
                            children.put(res.getResourceType().getSubCategory(), list);
                        }
                    } else if (compositeParent.getSubcategory().equals(res.getResourceType().getSubCategory())
                        && compositeParent.getParentResource().equals(res.getParentResource())) {
                        // Direct entries in a subcategory... now group them by autogroup (type)
                        if (children.containsKey(res.getResourceType())) {
                            children.get(res.getResourceType()).add(res);
                        } else {
                            ArrayList<Resource> list = new ArrayList<Resource>();
                            list.add(res);
                            children.put(res.getResourceType(), list);
                        }
                    }
                } else if (compositeParent.getResourceType() != null) {
                    if (compositeParent.getResourceType().equals(res.getResourceType())
                        && compositeParent.getParentResource().getId() == res.getParentResource().getId()) {
                        if (children.containsKey(res.getResourceType())) {
                            children.get(res.getResourceType()).add(res);
                        } else {
                            ArrayList<Resource> list = new ArrayList<Resource>();
                            list.add(res);
                            children.put(res.getResourceType(), list);
                        }
                    }
                }
            }

            for (Object rsc : children.keySet()) {
                if (rsc != null
                    && (rsc instanceof ResourceSubCategory || ((children.get(rsc).size() > 1 || (alwaysGroup && children
                        .get(rsc).size() == 1)) && ((AutoGroupComposite) parentNode.getData()).getSubcategory() != null))) {
                    double avail = 0;
                    List<Resource> entries = children.get(rsc);
                    for (Resource res : entries) {
                        avail += res.getCurrentAvailability().getAvailabilityType() == AvailabilityType.UP ? 1 : 0;
                    }
                    avail = avail / entries.size();

                    AutoGroupComposite agc = null;
                    if (rsc instanceof ResourceSubCategory) {
                        agc = new AutoGroupComposite(avail, compositeParent.getParentResource(),
                            (ResourceSubCategory) rsc, entries.size());
                    } else if (rsc instanceof ResourceType) {
                        agc = new AutoGroupComposite(avail, compositeParent.getParentResource(), (ResourceType) rsc,
                            entries.size(), false);
                    }
                    ResourceTreeNode node = new ResourceTreeNode(agc);
                    load(node, alwaysGroup);
                    if (!recursivelyLocked(node)) {
                        parentNode.getChildren().add(node);
                    }
                } else {
                    List<Resource> entries = children.get(rsc);
                    for (Resource res : entries) {
                        ResourceTreeNode node = new ResourceTreeNode(res);
                        load(node, alwaysGroup);
                        if (!recursivelyLocked(node)) {
                            parentNode.getChildren().add(node);
                        }
                    }
                }
            }
        }
    }

    public static boolean recursivelyLocked(ResourceTreeNode node) {
        if (node.getData() instanceof LockedResource) {

        } else if (node.getData() instanceof Resource) {
            return false;
        } else if (node.getData() instanceof AutoGroupComposite) {

        }

        boolean allLocked = true;
        for (ResourceTreeNode child : node.getChildren()) {
            if (!recursivelyLocked(child))
                allLocked = false;
        }
        return allLocked;
    }

    public ResourceTreeNode getTreeNode() {
        if (rootNode == null) {
            long start = System.currentTimeMillis();
            loadTree();
            log.debug("Loaded full tree in " + (System.currentTimeMillis() - start));
        }

        return rootNode;
    }

    public List<ResourceTreeNode> getRoots() {
        if (roots.isEmpty()) {
            roots.add(getTreeNode());
        }
        return roots;
    }

    public String getNodeTitle() {
        return nodeTitle;
    }

    public void setNodeTitle(String nodeTitle) {
        this.nodeTitle = nodeTitle;
    }

    private static Set<String> getDuplicateResourceTypeNames(Map<Object, List<Resource>> children, boolean alwaysGroup) {
        Set<String> resourceTypeNames = new HashSet();
        Set<String> dupResourceTypeNames = new HashSet();
        for (Object rsc : children.keySet()) {
            if (rsc != null
                && (rsc instanceof ResourceType && (children.get(rsc).size() > 1 || (alwaysGroup && children
                    .get(rsc).size() == 1)))) {
                String resourceTypeName = ((ResourceType)rsc).getName();
                if (resourceTypeNames.contains(resourceTypeName)) {
                    dupResourceTypeNames.add(resourceTypeName);
                }
                resourceTypeNames.add(resourceTypeName);
            }
        }
        return dupResourceTypeNames;
    }
}
