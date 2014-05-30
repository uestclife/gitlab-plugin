package com.dabsquared.gitlabjenkins;

import hudson.Extension;
import hudson.ExtensionPoint;
import hudson.model.*;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.util.*;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.security.csrf.CrumbExclusion;
import hudson.util.HttpResponses;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.acegisecurity.AccessDeniedException;
import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.kohsuke.stapler.HttpResponse;

/**
 *
 * @author Daniel Brooks
 */

@Extension
public class GitLabWebHook implements UnprotectedRootAction {

    private static final Logger LOGGER = Logger.getLogger(GitLabWebHook.class.getName());

    public static final String WEBHOOK_URL = "project";

    public String getIconFileName() {
        return null;
    }

    public String getDisplayName() {
        return null;
    }

    public String getUrlName() {
        return WEBHOOK_URL;
    }

    public void getDynamic(String projectName, StaplerRequest req, StaplerResponse res) {
        LOGGER.log(Level.FINE, "WebHook called.");

        String path = req.getRestOfPath();

        String[] splitURL = path.split("/");

        List<String> paths = new LinkedList<String>(Arrays.asList(splitURL));
        if(paths.size() > 0 && paths.get(0).equals("")) {
            paths.remove(0); //The first split is usually blank so we remove it.
        }


        String lastPath = paths.get(paths.size()-1);
        String firstPath = paths.get(0);

        String token = req.getParameter("token");

        //TODO: Check token authentication with project id. For now we are not using this.

        AbstractProject project = null;
        try {
            project = project(projectName, req, res);
        } catch (IOException e) {
            LOGGER.log(Level.FINE, "no such job {0}", projectName);
            throw HttpResponses.notFound();
        }

        if(lastPath.equals("status.json")) {
            String commitSHA1 = paths.get(1);
            this.generateStatusJSON(commitSHA1, project, req, res);
        } else if(lastPath.equals("build")) {
            String force = req.getParameter("force");
            String data = req.getParameter("data");
            this.generateBuild(data, project, req, res);
        } else if(lastPath.equals("status.png")) {
            String branch = req.getParameter("ref");
            String commitSHA1 = req.getParameter("sha1");
            try {
                this.generateStatusPNG(branch, commitSHA1, project, req, res);
            } catch (ServletException e) {
                e.printStackTrace();
                throw HttpResponses.error(500,"Could not generate an image.");
            } catch (IOException e) {
                e.printStackTrace();
                throw HttpResponses.error(500,"Could not generate an image.");
            }
        } else if(firstPath.equals("builds") && !lastPath.equals("status.json")) {
            AbstractBuild build = this.getBuildBySHA1(project, lastPath);
            if(build != null) {
                try {
                    res.sendRedirect2(build.getUrl());
                } catch (IOException e) {
                    try {
                        res.sendRedirect2(build.getBuildStatusUrl());
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
            }
        }

        throw HttpResponses.ok();

    }

    private void generateStatusJSON(String commitSHA1, AbstractProject project, StaplerRequest req, StaplerResponse rsp) {
        SCM scm = project.getScm();
        if(!(scm instanceof GitSCM)) {
            throw new IllegalArgumentException("This repo does not use git.");
        }

        AbstractBuild mainBuild = this.getBuildBySHA1(project, commitSHA1);

        JSONObject object = new JSONObject();
        object.put("sha", commitSHA1);

        if(mainBuild == null) {
            try {
                object.put("status", "pending");
                this.writeJSON(rsp, object);
                return;
            } catch (IOException e) {
                throw HttpResponses.error(500,"Could not generate response.");
            }
        }


        object.put("id", mainBuild.getNumber());

        BallColor currentBallColor = mainBuild.getIconColor().noAnime();

        //TODO: add staus of pending when we figure it out.
        if(mainBuild.isBuilding()) {
            object.put("status", "running");
        }else if(currentBallColor == BallColor.BLUE) {
            object.put("status", "success");
        }else if(currentBallColor == BallColor.ABORTED) {
            object.put("status", "failed");
        }else if(currentBallColor == BallColor.DISABLED) {
            object.put("status", "failed");
        }else if(currentBallColor == BallColor.GREY) {
            object.put("status", "failed");
        }else if(currentBallColor == BallColor.NOTBUILT) {
            object.put("status", "failed");
        }else if(currentBallColor == BallColor.RED) {
            object.put("status", "failed");
        }else if(currentBallColor == BallColor.YELLOW) {
            object.put("status", "failed");
        } else {
            object.put("status", "failed");
        }

        try {
            this.writeJSON(rsp, object);
        } catch (IOException e) {
            throw HttpResponses.error(500,"Could not generate response.");
        }
    }


    private void generateStatusPNG(String branch, String commitSHA1, AbstractProject project, StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        SCM scm = project.getScm();
        if(!(scm instanceof GitSCM)) {
            throw new IllegalArgumentException("This repo does not use git.");
        }

        AbstractBuild mainBuild = null;

        if(branch != null) {
            mainBuild = this.getBuildByBranch(project, branch);
        } else if(commitSHA1 != null) {
            mainBuild = this.getBuildBySHA1(project, commitSHA1);
        }


        if(mainBuild == null) {
            rsp.sendRedirect2(Jenkins.getInstance().getRootUrl() + "/plugin/gitlab-jenkins/images/unknown.png");
        }

        assert mainBuild != null;
        BallColor currentBallColor = mainBuild.getIconColor().noAnime();

        if(mainBuild.isBuilding()) {
            rsp.sendRedirect2(Jenkins.getInstance().getRootUrl() + "/plugin/gitlab-jenkins/images/running.png");
        }else if(currentBallColor == BallColor.BLUE) {
            rsp.sendRedirect2(Jenkins.getInstance().getRootUrl() + "/plugin/gitlab-jenkins/images/success.png");
        }else if(currentBallColor == BallColor.ABORTED) {
            rsp.sendRedirect2(Jenkins.getInstance().getRootUrl() + "/plugin/gitlab-jenkins/images/unknown.png");
        }else if(currentBallColor == BallColor.DISABLED) {
            rsp.sendRedirect2(Jenkins.getInstance().getRootUrl() + "/plugin/gitlab-jenkins/images/unknown.png");
        }else if(currentBallColor == BallColor.GREY) {
            rsp.sendRedirect2(Jenkins.getInstance().getRootUrl() + "/plugin/gitlab-jenkins/images/unknown.png");
        }else if(currentBallColor == BallColor.NOTBUILT) {
            rsp.sendRedirect2(Jenkins.getInstance().getRootUrl() + "/plugin/gitlab-jenkins/images/unknown.png");
        }else if(currentBallColor == BallColor.RED) {
            rsp.sendRedirect2(Jenkins.getInstance().getRootUrl() + "/plugin/gitlab-jenkins/images/failed.png");
        }else if(currentBallColor == BallColor.YELLOW) {
            rsp.sendRedirect2(Jenkins.getInstance().getRootUrl() + "/plugin/gitlab-jenkins/images/unknown.png");
        } else {
            rsp.sendRedirect2(Jenkins.getInstance().getRootUrl() + "/plugin/gitlab-jenkins/images/unknown.png");
        }

    }


    /**
     * Take the GitLab Data and parse through it.
     * {
     #     "before": "95790bf891e76fee5e1747ab589903a6a1f80f22",
     #     "after": "da1560886d4f094c3e6c9ef40349f7d38b5d27d7",
     #     "ref": "refs/heads/master",
     #     "commits": [
     #       {
     #         "id": "b6568db1bc1dcd7f8b4d5a946b0b91f9dacd7327",
     #         "message": "Update Catalan translation to e38cb41.",
     #         "timestamp": "2011-12-12T14:27:31+02:00",
     #         "url": "http://localhost/diaspora/commits/b6568db1bc1dcd7f8b4d5a946b0b91f9dacd7327",
     #         "author": {
     #           "name": "Jordi Mallach",
     #           "email": "jordi@softcatala.org",
     #         }
     #       }, .... more commits
     #     ]
     #   }
     * @param data
     */
    private void generateBuild(String data, AbstractProject project, StaplerRequest req, StaplerResponse rsp) {
        JSONObject json = JSONObject.fromObject(data);
        LOGGER.log(Level.FINE, "data: {0}", json.toString(4));

        if(data == null) {
            return;
        }

        GitLabPushRequest request = GitLabPushRequest.create(json);

        String repositoryUrl = request.getRepository().getUrl();
        if (repositoryUrl == null) {
            LOGGER.log(Level.WARNING, "No repository url found.");
            return;
        }

        Authentication old = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(ACL.SYSTEM);
        try {
            for (AbstractProject<?, ?> job : Jenkins.getInstance().getAllItems(AbstractProject.class)) {
                GitLabPushTrigger trigger = job.getTrigger(GitLabPushTrigger.class);
                if (trigger == null) {
                    continue;
                }
                trigger.onPost(request);
            }
        } finally {
            SecurityContextHolder.getContext().setAuthentication(old);
        }
    }


    private AbstractBuild getBuildBySHA1(AbstractProject project, String commitSHA1) {
        AbstractBuild mainBuild = null;

        List<AbstractBuild> builds = project.getBuilds();
        for(AbstractBuild build : builds) {
            BuildData data = build.getAction(BuildData.class);

            if(data.getLastBuiltRevision().getSha1String().equals(commitSHA1)) {
                mainBuild = build;
                break;
            }
        }

        return mainBuild;
    }

    private AbstractBuild getBuildByBranch(AbstractProject project, String branch) {
        AbstractBuild mainBuild = null;

        List<AbstractBuild> builds = project.getBuilds();
        for(AbstractBuild build : builds) {
            BuildData data = build.getAction(BuildData.class);
            hudson.plugins.git.util.Build branchBuild = data.getBuildsByBranchName().get("origin/" + branch);
            if(branchBuild != null) {
                int buildNumber = branchBuild.getBuildNumber();
                mainBuild = project.getBuildByNumber(buildNumber);
                break;
            }
        }

        return mainBuild;
    }


    /**
     *
     * @param rsp The stapler response to write the output to.
     * @throws IOException
     */
    private  void writeJSON(StaplerResponse rsp, JSONObject jsonObject) throws IOException {
        rsp.setContentType("application/json");
        PrintWriter w = rsp.getWriter();

        if(jsonObject == null) {
            w.write("null");
        } else {
            w.write(jsonObject.toString());
        }

        w.flush();
        w.close();

    }


    /**
     *
     * @param job The job name
     * @param req The stapler request asking for the project
     * @param rsp The stapler response asking for the project
     * @return A project that matches the information.
     * @throws IOException
     * @throws HttpResponses.HttpResponseException
     */
    @SuppressWarnings("deprecation")
    private AbstractProject<?,?> project(String job, StaplerRequest req, StaplerResponse rsp) throws IOException, HttpResponses.HttpResponseException {
        AbstractProject<?,?> p;
        SecurityContext orig = ACL.impersonate(ACL.SYSTEM);
        try {
            p = Jenkins.getInstance().getItemByFullName(job, AbstractProject.class);
        } finally {
            SecurityContextHolder.setContext(orig);
        }
        if (p == null) {
            LOGGER.log(Level.FINE, "no such job {0}", job);
            throw HttpResponses.notFound();
        }
        return p;
    }





}
