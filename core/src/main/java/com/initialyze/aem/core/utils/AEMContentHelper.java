package com.initialyze.aem.core.utils;

import com.day.cq.tagging.InvalidTagFormatException;
import com.day.cq.tagging.Tag;
import com.day.cq.tagging.TagManager;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.PageManager;
import com.day.cq.wcm.api.WCMException;
import com.drew.lang.annotations.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.jackrabbit.oak.commons.PropertiesUtil;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

import static com.day.cq.commons.jcr.JcrConstants.JCR_CONTENT;
import static com.day.cq.commons.jcr.JcrConstants.JCR_LASTMODIFIED;

public class AEMContentHelper {

    private static final Logger log = LoggerFactory.getLogger(AEMContentHelper.class);
    private static final Pattern NONLATIN = Pattern.compile("[^\\w_-]");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\p{Punct}&&[^-]]");

    public enum MergeMode {
        CREATE_NODES_AND_OVERWRITE_PROPERTIES(true, true, true, false),
        CREATE_NODES_AND_OVERWRITE_PROPERTIES_AND_APPEND_ARRAYS(true, true, true, true),
        CREATE_NODES_AND_MERGE_PROPERTIES(true, true, false, false),
        CREATE_ONLY_SKIP_EXISTING(true, false, false, false),
        OVERWRITE_EXISTING_ONLY(false, true, true, false),
        OVERWRITE_EXISTING_ONLY_AND_APPEND_ARRAYS(false, true, true, true),
        MERGE_EXISTING_ONLY(false, true, false, false),
        DO_NOTHING(false, false, false, false);

        final boolean create;
        final boolean update;
        final boolean overwriteProps;
        final boolean appendArrays;

        MergeMode(boolean c, boolean u, boolean o, boolean a) {
            this.create = c;
            this.update = u;
            this.overwriteProps = o;
            this.appendArrays = a;
        }
    }

    /**
     * Generates a URL-friendly slug from the given input string.
     * This method replaces separators with hyphens, removes non-latin characters,
     * normalizes the string, and ensures the result is in lowercase. It also removes
     * redundant or trailing hyphens.
     *
     * @param input the input string to be converted into a slug
     * @return a URL-friendly slug as a lowercase string
     */
    public static String makeSlug(String input) {
        String noseparators = SEPARATORS.matcher(input).replaceAll("-");
        String normalized = Normalizer.normalize(noseparators, Normalizer.Form.NFD);
        String slug = NONLATIN.matcher(normalized).replaceAll("");
        return slug.toLowerCase(Locale.ENGLISH).replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
    }


    /**
     * Creates or resolves tags based on the provided tag ID and title mappings.
     * This method ensures that tags are either found or created under a specified
     * parent tag. Tags are uniquely identified and generated according to their
     * provided or transformed IDs. If a parent tag does not exist, it will be
     * created. All created or resolved tag IDs are returned in the resulting list.
     *
     * @param mapTagTitleAndId a map containing tag titles as keys and their corresponding
     *                         IDs (if any) as values. If an ID is not provided, it will
     *                         be generated based on the title.
     * @param parentTagId      the ID of the parent tag under which the tags should be
     *                         created or resolved. If empty or null, tags are created at
     *                         the root level.
     * @param parentTagTitle   the title of the parent tag, used if the parent tag does
     *                         not already exist. It is optional and only relevant if the
     *                         parentTagId is specified.
     * @param tagManager       an instance of TagManager used for resolving or creating tags.
     * @return a list of IDs of the resolved or created tags.
     * @throws RuntimeException          if the tag cannot be created due to an unexpected error.
     * @throws InvalidTagFormatException if a tag ID or title has an invalid format.
     */
    public static List<String> createTags(@NotNull Map<String, String> mapTagTitleAndId, @NotNull String parentTagId, String parentTagTitle, TagManager tagManager) throws RuntimeException, InvalidTagFormatException {

        List<String> strTagIdsFoundOrCreated = new ArrayList<>();
        final String tagIdPrefix;
        if (StringUtils.isNotBlank(parentTagId)) {
            Tag parentTag = tagManager.resolve(parentTagId);
            if (parentTag != null) {
                tagIdPrefix = parentTag.getTagID();
            } else {
                String tagTitle = StringUtils.isNotBlank(parentTagTitle) ? parentTagTitle : parentTagId;
                parentTag = tagManager.createTag(parentTagId, tagTitle, "Created by Initialyze Importer", true);
                tagIdPrefix = parentTag.getTagID();
            }
        } else {
            tagIdPrefix = "";
        }

        mapTagTitleAndId.entrySet().forEach((tagEntry) -> {
            String tagIdFromMap = StringUtils.isNotBlank(tagEntry.getValue()) ? tagEntry.getValue() : convertToHyphenatedLowerCase(tagEntry.getKey());
            String tagId = (StringUtils.isNotBlank(tagIdPrefix) && !tagIdFromMap.startsWith(tagIdPrefix)) ? tagIdPrefix.concat("/").concat(tagIdFromMap) : tagIdFromMap;
            Tag tag = tagManager.resolve(tagId);

            //create tag if createTags is enabled
            if (tag == null) {
                try {
                    tag = tagManager.createTag(tagId, tagEntry.getKey(), "Created by Initialyzer Importer", true);
                } catch (InvalidTagFormatException e) {
                    if (log.isErrorEnabled()) {
                        log.error("Error creating tag with title for id: {}, title: {} > ", tagId, tagEntry.getKey(), e);
                    }
                    throw new RuntimeException("Error creating tag with title:" + tagEntry.getKey() + " with id: " + tagId, e);
                }
            }
            if (tag != null) {
                strTagIdsFoundOrCreated.add(tag.getTagID());
            }
        });

        return strTagIdsFoundOrCreated;
    }

    /**
     * Creates or resolves tags based on the provided tag titles and parent path.
     * This method ensures that tags are created or resolved under a specified
     * parent path. If a parent tag does not exist, it will use the provided parent
     * title to create one. All resolved or created tag IDs are returned in the resulting list.
     *
     * @param tagTitles     an array of tag titles to create or resolve.
     * @param tagParentPath the parent path under which the tags should be created or resolved.
     * @param tagManager    an instance of TagManager used for resolving or creating tags.
     * @return a list of IDs for the resolved or created tags.
     * @throws InvalidTagFormatException if a tag title has an invalid format.
     */
    public static List<String> createTags(String[] tagTitles, String tagParentPath, TagManager tagManager) throws InvalidTagFormatException {
        return createTags(tagTitles, tagParentPath, null, tagManager);
    }

    /**
     * Creates or resolves tags based on the provided tag titles and parent path.
     * This method ensures that tags are either resolved or created under a specified
     * parent path and title. If a parent tag does not exist, it will use the provided
     * parent title to create one. All resolved or created tag IDs are returned in the
     * resulting list.
     *
     * @param tagTitles      an array of tag titles to create or resolve.
     * @param tagParentPath  the path of the parent tag under which the tags should be created or resolved.
     * @param tagParentTitle the title of the parent tag. It is used only if the parent
     *                       tag does not already exist.
     * @param tagManager     an instance of TagManager used for resolving or creating tags.
     * @return a list of IDs for the resolved or created tags.
     * @throws InvalidTagFormatException if a tag title has an invalid format.
     */
    public static List<String> createTags(String[] tagTitles, String tagParentPath, String tagParentTitle, TagManager tagManager) throws InvalidTagFormatException {
        Map<String, String> mapTagTitleAndId = new LinkedHashMap<>();
        for (String tagTitle : tagTitles) {
            mapTagTitleAndId.put(tagTitle, null);
        }
        return createTags(mapTagTitleAndId, tagParentPath, tagParentTitle, tagManager);
    }

    /**
     * Creates or updates a page at the specified path using the provided parameters.
     * If the page does not exist and the merge mode supports creation, a new page will be created.
     * If the page already exists, it will not be modified or replaced unless allowed by the merge mode.
     *
     * @param parentPagePath   the path of the parent page under which the page should be created or updated
     * @param pageName         the name of the page to create or update
     * @param templatePath     the path to the template to use for the page
     * @param pageTitle        the title of the page to create or update
     * @param mergeMode        the merge mode determining whether the page should be created or updated
     * @param resourceResolver the resource resolver used to adapt and resolve resources
     * @return the created or resolved Page instance, or null if the operation fails
     */
    public static Page createOrUpdatePage(String parentPagePath, String pageName, String templatePath, String pageTitle, MergeMode mergeMode, ResourceResolver resourceResolver) {

        Page page = null;
        try {
            PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
            String pagePath = parentPagePath + "/" + pageName;
            page = pageManager.getPage(pagePath);
            if (page == null && mergeMode.create) {
                log.debug("page: {} doesn't exist, creating", pagePath);
                page = pageManager.create(parentPagePath, pageName, templatePath, pageTitle, true);
            } else {
                log.info("page: {} might be already exists, not deleting or creating >", pagePath);
//                //if no updates required, then skip deleting
//                if(mergeMode.update) {
//                    pageManager.delete(page, Boolean.FALSE, Boolean.TRUE);
//                    page = pageManager.create(parentPagePath, pageName, templatePath, pageTitle, true);
//                }
            }
        } catch (WCMException e) {
            log.error(">>>> [WCMException] in AEMContentHelper > ", e);
        }
        return page;
    }

    /**
     * Creates or retrieves a page at the specified path without merging or modifying any existing pages.
     * If the page does not exist, it creates a new page using the provided template and title.
     *
     * @param parentPagePath   the path of the parent page under which the page should be created
     * @param pageName         the name of the page to create or retrieve
     * @param templatePath     the path to the template to use for the page
     * @param pageTitle        the title of the page to create
     * @param resourceResolver the resource resolver used to adapt and resolve resources
     * @return the created or retrieved Page instance, or null if the operation fails
     */
    public static Page createOrUpdatePageWithoutMerge(String parentPagePath, String pageName, String templatePath, String pageTitle, ResourceResolver resourceResolver) {

        Page page = null;
        try {
            PageManager pageManager = resourceResolver.adaptTo(PageManager.class);
            String pagePath = parentPagePath + "/" + pageName;
            page = pageManager.getPage(pagePath);
            if (page == null) {
                log.debug("page: {} doesn't exist, creating", pagePath);
                page = pageManager.create(parentPagePath, pageName, templatePath, pageTitle, true);
            } else {
                log.info("page: {} might be already exists, not deleting or creating >", pagePath);
            }
        } catch (WCMException e) {
            log.error(">>>> [WCMException] in AEMContentHelper > ", e);
        }
        return page;
    }

    /**
     * Updates a property on a given content node based on the provided type and merge mode.
     * Depending on the merge mode settings, the method can create, update, or overwrite the property.
     * If the specified type is not supported, a log entry will be made and no updates will occur.
     *
     * @param contentNode   the content node to update
     * @param propertyName  the name of the property to update
     * @param propertyValue the value to set for the property, its type must align with the provided type parameter
     * @param type          the type of the property being updated (e.g., "date", "string")
     * @param mergeMode     the merge mode specifying create, update, or overwrite behavior
     * @throws RepositoryException if an error occurs while accessing the content repository
     */
    public static void updateProperty(Node contentNode, String propertyName, Object propertyValue, String type, MergeMode mergeMode) throws RepositoryException {
        switch (type) {
            case "date":
                if (!contentNode.hasProperty(propertyName) && mergeMode.create) {
                    contentNode.setProperty(propertyName, (Calendar) propertyValue);
                } else if (mergeMode.overwriteProps || mergeMode.update) {
                    contentNode.setProperty(propertyName, (Calendar) propertyValue);
                }
                break;
            case "string":
                if (!contentNode.hasProperty(propertyName) && mergeMode.create) {
                    contentNode.setProperty(propertyName, (String) propertyValue);
                } else if (mergeMode.overwriteProps || mergeMode.update) {
                    contentNode.setProperty(propertyName, (String) propertyValue);
                }
                break;
            default:
                log.info("Type {} not supported", type);
        }
    }

    /**
     * Updates or creates a property on the given content node based on the provided property name, value,
     * and merge mode. This method allows for flexible property management, including creating new properties,
     * updating existing ones, or appending array values, depending on the merge mode configuration.
     *
     * @param contentNode   the content node where the property will be updated or created
     * @param propertyName  the name of the property to update or create
     * @param propertyValue the array of string values to set for the property
     * @param mergeMode     the merge mode specifying the behavior for property creation, update, or appending values
     * @throws RepositoryException if there is an error accessing or modifying the content repository
     */
    public static void updateProperty(Node contentNode, String propertyName, String[] propertyValue, MergeMode mergeMode) throws RepositoryException {
        if (contentNode.hasProperty(propertyName)) {
            Property property = contentNode.getProperty(propertyName);
            if (!contentNode.hasProperty(propertyName) && mergeMode.create) {
                contentNode.setProperty(propertyName, propertyValue);
            } else if (mergeMode.update && mergeMode.appendArrays) {
                String[] currentValues = PropertiesUtil.toStringArray(property.getValues());
                Set<String> mergedSet = new HashSet<>(Arrays.asList(currentValues));
                mergedSet.addAll(Arrays.asList(propertyValue));
                contentNode.setProperty(propertyName, mergedSet.toArray(new String[0]));
            } else if (mergeMode.update) {
                contentNode.setProperty(propertyName, propertyValue);
            }
        } else if (mergeMode.update || mergeMode.create || mergeMode.appendArrays) {
            contentNode.setProperty(propertyName, propertyValue);
        }
    }

    /**
     * Retrieves the last replicated date of a given resource if it has been activated.
     * This method checks the 'cq:lastReplicationAction' property to confirm whether the replication action was "Activate".
     * If the action matches, it retrieves the 'cq:lastReplicated' property as a Calendar object.
     * If the 'cq:lastReplicationAction' property is not found directly on the resource, it checks the child 'jcr:content' node.
     *
     * @param resource the resource for which the last replicated date is to be determined
     * @return the last replicated date as a Calendar object if the resource has been activated; otherwise, returns null
     */
    public static Calendar getLastReplicatedDate(Resource resource) {
        ValueMap properties = resource.getValueMap();
        String replicationAction = properties.get("cq:lastReplicationAction", String.class);
        if (replicationAction == null) {
            Resource contentResource = resource.getChild(JCR_CONTENT);
            if (contentResource != null) {
                properties = contentResource.getValueMap();
                replicationAction = properties.get("cq:lastReplicationAction", String.class);
            }
        }

        Calendar lastReplicated = null;
        if (Strings.CS.equals(replicationAction, "Activate")) {
            lastReplicated = properties.get("cq:lastReplicated", Calendar.class);
        }
        return lastReplicated;
    }

    /**
     * Retrieves the last modified date of a given resource.
     * This method checks the 'jcr:lastModified' property on the resource
     * and, if not found, attempts to check for the same property on the
     * child 'jcr:content' node if it exists.
     *
     * @param resource the resource for which the last modified date is to be determined
     * @return the last modified date as a Calendar object if found; otherwise, returns null
     */
    public static Calendar getLastModifiedDate(Resource resource) {
        ValueMap properties = resource.getValueMap();
        Calendar lastModified = properties.get(JCR_LASTMODIFIED, Calendar.class);
        if (lastModified == null) {
            Resource contentResource = resource.getChild(JCR_CONTENT);
            if (contentResource != null) {
                properties = contentResource.getValueMap();
                lastModified = properties.get(JCR_LASTMODIFIED, Calendar.class);
            }
        }
        return lastModified;
    }

    /**
     * Converts the input string to a hyphenated, lowercase format.
     * The method replaces spaces and special characters with hyphens and converts all alphabetic characters to lowercase.
     *
     * @param inputString the string to be converted; must not be null
     * @return a hyphenated and lowercase version of the input string
     */
    public static String convertToHyphenatedLowerCase(@NotNull String inputString) {
        // Convert to lowercase
        String lowercaseString = inputString.toLowerCase();

        // Return the modified string
        return lowercaseString.replaceAll("[^a-z0-9]+", "-");
    }
}
