/*
 * #%L
 * ACS AEM Commons Bundle
 * %%
 * Copyright (C) 2013 Adobe
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package com.adobe.acs.commons.packaging.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.sling.SlingServlet;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.commons.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.acs.commons.packaging.PackageHelper;
import com.day.jcr.vault.fs.io.AccessControlHandling;
import com.day.jcr.vault.packaging.JcrPackage;
import com.day.jcr.vault.packaging.JcrPackageDefinition;
import com.day.jcr.vault.packaging.Packaging;

/**
 * ACS AEM Commons - Authorizable Packager Servlet
 * Servlet end-point used to create CRX packages of authorizables based on the underlying resource's configuration.
 */
@SuppressWarnings("serial")
@SlingServlet(
        methods = { "POST" },
        resourceTypes = { "acs-commons/components/utilities/packager/authorizable-packager" },
        selectors = { "package" },
        extensions = { "json" }
)
public class AuthorizablePackagerServletImpl extends SlingAllMethodsServlet {
    private static final Logger log = LoggerFactory.getLogger(AuthorizablePackagerServletImpl.class);

    private static final String PACKAGE_NAME = "packageName";

    private static final String PACKAGE_GROUP_NAME = "packageGroupName";

    private static final String PACKAGE_VERSION = "packageVersion";

    private static final String PACKAGE_DESCRIPTION = "packageDescription";

    private static final String PACKAGE_ACL_HANDLING = "packageACLHandling";

    private static final String CONFLICT_RESOLUTION = "conflictResolution";

    private static final String DEFAULT_PACKAGE_NAME = "authorizables";

    private static final String DEFAULT_PACKAGE_GROUP_NAME = "Authorizables";

    private static final String DEFAULT_PACKAGE_VERSION = "1.0.0";

    private static final String DEFAULT_PACKAGE_DESCRIPTION = "Authorizable Package initially defined by a ACS AEM Commons - "
            + "Authorizable Packager configuration.";

    private static final String QUERY_PACKAGE_THUMBNAIL_RESOURCE_PATH =
            "/apps/acs-commons/components/utilities/packager/authorizable-packager/definition/package-thumbnail.png";

    @Reference
    private Packaging packaging;

    @Reference
    private PackageHelper packageHelper;

    @Override
    public final void doPost(final SlingHttpServletRequest request,
                             final SlingHttpServletResponse response) throws IOException {

        final ResourceResolver resourceResolver = request.getResourceResolver();
        final boolean preview = Boolean.parseBoolean(request.getParameter("preview"));

        log.debug("Preview mode: {}", preview);

        final ValueMap properties = this.getProperties(request);

        try {
            final List<String> paths = this.findPaths(resourceResolver,
                    properties.get("authorizableIds", new String[0]));

            final Map<String, String> packageDefinitionProperties = new HashMap<String, String>();

            // ACL Handling
            packageDefinitionProperties.put(JcrPackageDefinition.PN_AC_HANDLING,
                    properties.get(PACKAGE_ACL_HANDLING, AccessControlHandling.OVERWRITE.toString()));

            // Package Description
            packageDefinitionProperties.put(
                    JcrPackageDefinition.PN_DESCRIPTION,
                    properties.get(PACKAGE_DESCRIPTION, DEFAULT_PACKAGE_DESCRIPTION));

            if (preview) {
                // Handle preview mode
                response.getWriter().print(packageHelper.getPreviewJSONForPaths(paths));
            } else if (paths == null || paths.isEmpty()) {
                // Do not create empty packages; This will only clutter up CRX Package Manager
                response.getWriter().print(packageHelper.getErrorJSON("Refusing to create a package with no filter "
                        + "set rules."));
            } else {
                // Create JCR Package; Defaults should always be passed in via Request Parameters, but just in case
                final JcrPackage jcrPackage = packageHelper.createPackageForPaths(paths,
                        request.getResourceResolver().adaptTo(Session.class),
                        properties.get(PACKAGE_GROUP_NAME, DEFAULT_PACKAGE_GROUP_NAME),
                        properties.get(PACKAGE_NAME, DEFAULT_PACKAGE_NAME),
                        properties.get(PACKAGE_VERSION, DEFAULT_PACKAGE_VERSION),
                        PackageHelper.ConflictResolution.valueOf(properties.get(CONFLICT_RESOLUTION,
                                PackageHelper.ConflictResolution.IncrementVersion.toString())),
                        packageDefinitionProperties
                );

                // Add thumbnail to the package definition
                packageHelper.addThumbnail(jcrPackage,
                        request.getResourceResolver().getResource(QUERY_PACKAGE_THUMBNAIL_RESOURCE_PATH));

                log.debug("Successfully created JCR package");
                response.getWriter().print(
                        packageHelper.getSuccessJSON(jcrPackage));
            }
        } catch (RepositoryException ex) {
            log.error("Repository error while creating Query Package", ex);
            response.getWriter().print(packageHelper.getErrorJSON(ex.getMessage()));
        } catch (IOException ex) {
            log.error("IO error while creating Query Package", ex);
            response.getWriter().print(packageHelper.getErrorJSON(ex.getMessage()));
        } catch (JSONException ex) {
            log.error("JSON error while creating Query Package response", ex);
            response.getWriter().print(packageHelper.getErrorJSON(ex.getMessage()));
        }
    }

    /**
     * Gets the properties saved to the Query Packager Page's jcr:content node.
     *
     * @param request the request obj
     * @return a ValueMap representing the properties
     */
    private ValueMap getProperties(final SlingHttpServletRequest request) {
        if (request.getResource().getChild("configuration") == null) {
            log.warn("Authorizable Packager Configuration node could not be found for: {}", request.getResource());
            return new ValueMapDecorator(new HashMap<String, Object>());
        } else {
            return request.getResource().getChild("configuration").adaptTo(ValueMap.class);
        }
    }

    private List<String> findPaths(final ResourceResolver resourceResolver,
                                         final String[] authorizableIds) throws RepositoryException {

        final UserManager userManager = resourceResolver.adaptTo(UserManager.class);

        final List<String> paths = new ArrayList<String>();

        for (final String authorizableId : authorizableIds) {
            try {
                final Authorizable authorizable = userManager.getAuthorizable(authorizableId);
                if (authorizable != null) {
                    paths.add(authorizable.getPath());
                }
            } catch (RepositoryException e) {
                log.warn("Unable to find path for authorizable " + authorizableId, e);
            }
        }

        return paths;
    }
}
