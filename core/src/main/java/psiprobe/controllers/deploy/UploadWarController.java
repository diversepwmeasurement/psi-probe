/*
 * Licensed under the GPL License. You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 * 
 * THIS PACKAGE IS PROVIDED "AS IS" AND WITHOUT ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING,
 * WITHOUT LIMITATION, THE IMPLIED WARRANTIES OF MERCHANTIBILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE.
 */

package psiprobe.controllers.deploy;

import org.apache.catalina.Context;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.fileupload.servlet.ServletRequestContext;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.view.InternalResourceView;

import psiprobe.controllers.TomcatContainerController;
import psiprobe.controllers.jsp.DisplayJspController;
import psiprobe.model.jsp.Summary;

import java.io.File;
import java.net.URL;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Uploads and installs web application from a .WAR.
 * 
 * @author Vlad Ilyushchenko
 * @author Mark Lewis
 */
public class UploadWarController extends TomcatContainerController {

  /** The Constant logger. */
  private static final Logger logger = LoggerFactory.getLogger(UploadWarController.class);

  @Override
  protected ModelAndView handleRequestInternal(HttpServletRequest request,
      HttpServletResponse response) throws Exception {

    if (FileUploadBase.isMultipartContent(new ServletRequestContext(request))) {

      File tmpWar = null;
      String contextName = null;
      boolean update = false;
      boolean compile = false;
      boolean discard = false;

      // parse multipart request and extract the file
      FileItemFactory factory =
          new DiskFileItemFactory(1048000, new File(System.getProperty("java.io.tmpdir")));
      ServletFileUpload upload = new ServletFileUpload(factory);
      upload.setSizeMax(-1);
      upload.setHeaderEncoding("UTF8");
      try {
        List<FileItem> fileItems = upload.parseRequest(request);
        for (FileItem fi : fileItems) {
          if (!fi.isFormField()) {
            if (fi.getName() != null && fi.getName().length() > 0) {
              tmpWar =
                  new File(System.getProperty("java.io.tmpdir"),
                      FilenameUtils.getName(fi.getName()));
              fi.write(tmpWar);
            }
          } else if ("context".equals(fi.getFieldName())) {
            contextName = fi.getString();
          } else if ("update".equals(fi.getFieldName()) && "yes".equals(fi.getString())) {
            update = true;
          } else if ("compile".equals(fi.getFieldName()) && "yes".equals(fi.getString())) {
            compile = true;
          } else if ("discard".equals(fi.getFieldName()) && "yes".equals(fi.getString())) {
            discard = true;
          }
        }
      } catch (Exception e) {
        logger.error("Could not process file upload", e);
        request.setAttribute(
            "errorMessage",
            getMessageSourceAccessor().getMessage("probe.src.deploy.war.uploadfailure",
                new Object[] {e.getMessage()}));
        if (tmpWar != null && tmpWar.exists()) {
          tmpWar.delete();
        }
        tmpWar = null;
      }

      String errMsg = null;

      if (tmpWar != null) {
        try {
          if (tmpWar.getName().endsWith(".war")) {

            if (contextName == null || contextName.length() == 0) {
              String warFileName = tmpWar.getName().replaceAll("\\.war$", "");
              contextName = "/" + warFileName;
            }

            contextName = getContainerWrapper().getTomcatContainer().formatContextName(contextName);

            /*
             * pass the name of the newly deployed context to the presentation layer using this name
             * the presentation layer can render a url to view compilation details
             */
            String visibleContextName = "".equals(contextName) ? "/" : contextName;
            request.setAttribute("contextName", visibleContextName);

            if (update
                && getContainerWrapper().getTomcatContainer().findContext(contextName) != null) {

              logger.debug("updating {}: removing the old copy", contextName);
              getContainerWrapper().getTomcatContainer().remove(contextName);
            }

            if (getContainerWrapper().getTomcatContainer().findContext(contextName) == null) {
              // move the .war to tomcat application base dir
              String destWarFilename =
                  getContainerWrapper().getTomcatContainer().formatContextFilename(contextName);
              File destWar =
                  new File(getContainerWrapper().getTomcatContainer().getAppBase(), destWarFilename
                      + ".war");

              FileUtils.moveFile(tmpWar, destWar);

              // let Tomcat know that the file is there
              getContainerWrapper().getTomcatContainer().installWar(contextName,
                  new URL("jar:" + destWar.toURI().toURL().toString() + "!/"));

              Context ctx = getContainerWrapper().getTomcatContainer().findContext(contextName);
              if (ctx == null) {
                errMsg =
                    getMessageSourceAccessor().getMessage("probe.src.deploy.war.notinstalled",
                        new Object[] {visibleContextName});
              } else {
                request.setAttribute("success", Boolean.TRUE);
                // Logging action
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                String name = auth.getName(); // get username logger
                logger.info(getMessageSourceAccessor().getMessage("probe.src.log.deploywar"), name, contextName);
                if (discard) {
                  getContainerWrapper().getTomcatContainer().discardWorkDir(ctx);
                  logger.info(getMessageSourceAccessor().getMessage("probe.src.log.discardwork"), name, contextName);
                }
                if (compile) {
                  Summary summary = new Summary();
                  summary.setName(ctx.getName());
                  getContainerWrapper().getTomcatContainer().listContextJsps(ctx, summary, true);
                  request.getSession(true).setAttribute(DisplayJspController.SUMMARY_ATTRIBUTE,
                      summary);
                  request.setAttribute("compileSuccess", Boolean.TRUE);
                }
              }

            } else {
              errMsg =
                  getMessageSourceAccessor().getMessage("probe.src.deploy.war.alreadyExists",
                      new Object[] {visibleContextName});
            }
          } else {
            errMsg = getMessageSourceAccessor().getMessage("probe.src.deploy.war.notWar.failure");
          }
        } catch (Exception e) {
          errMsg =
              getMessageSourceAccessor().getMessage("probe.src.deploy.war.failure",
                  new Object[] {e.getMessage()});
          logger.error("Tomcat throw an exception when trying to deploy", e);
        } finally {
          if (errMsg != null) {
            request.setAttribute("errorMessage", errMsg);
          }
          tmpWar.delete();
        }
      }
    }
    return new ModelAndView(new InternalResourceView(getViewName()));
  }

}
