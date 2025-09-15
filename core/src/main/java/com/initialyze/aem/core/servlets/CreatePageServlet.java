package com.initialyze.aem.core.servlets;

import com.day.cq.wcm.api.Page;
import com.drew.lang.annotations.NotNull;
import com.initialyze.aem.core.utils.AEMContentHelper;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import java.io.IOException;

@Component(
        service = Servlet.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Create page via template",
                "sling.servlet.methods=POST",
                "sling.servlet.paths=/bin/aem/create-page"
        }
)
public class CreatePageServlet extends org.apache.sling.api.servlets.SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(CreatePageServlet.class);
    private static final String SUBSERVICE = "aem-mcp-writer-service";

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Override
    protected void doPost(SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response)
            throws ServletException, IOException {

        String parentPath = request.getParameter("parentPath");
        String pageName = request.getParameter("pageName");
        String title = request.getParameter("title");
        String template = request.getParameter("template");

        if (StringUtils.isAnyBlank(parentPath, pageName, title, template)) {
            LOGGER.error("Missing required parameters");
            response.setStatus(400);
            response.getWriter().write("{\"error\":\"Missing parameter(s): parentPath, pageName, title, template\"}");
            return;
        }

        try (ResourceResolver serviceResolver =
                     resourceResolverFactory.getServiceResourceResolver(
                             java.util.Collections.singletonMap(
                                     ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            Page page = AEMContentHelper.createOrUpdatePageWithoutMerge(parentPath, pageName, template, title, serviceResolver);
            serviceResolver.commit();
            LOGGER.info("Created new page with name '{}'", pageName);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":\"ok\",\"path\":\"" + page.getPath() + "\"}");
        } catch (Exception e) {
            LOGGER.error("Error creating page with name '{}'", pageName, e);
            response.setStatus(500);
            response.getWriter().write("{\"error\":\"" + e.getMessage() + "\" }");
        }
    }
}

