package hudson.maven;

import hudson.FilePath.FileCallable;
import hudson.model.AbstractBuild;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.remoting.VirtualChannel;
import hudson.util.IOException2;
import org.apache.maven.embedder.MavenEmbedderException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link Build} for {@link MavenModuleSet}.
 *
 * <p>
 * A "build" of {@link MavenModuleSet} consists of:
 *
 * <ol>
 * <li>Update the workspace.
 * <li>Parse POMs
 * <li>Trigger module builds.
 * </ol>
 *
 * This object remembers the changelog and what {@link MavenBuild}s are done
 * on this.
 *  
 * @author Kohsuke Kawaguchi
 */
public final class MavenModuleSetBuild extends AbstractBuild<MavenModuleSet,MavenModuleSetBuild> {

    public MavenModuleSetBuild(MavenModuleSet job) throws IOException {
        super(job);
    }

    MavenModuleSetBuild(MavenModuleSet project, File buildDir) throws IOException {
        super(project, buildDir);
    }

    public void run() {
        run(new RunnerImpl());
    }

    /**
     * The sole job of the {@link MavenModuleSet} build is to update SCM
     * and triggers module builds.
     */
    private class RunnerImpl extends AbstractRunner {
        protected Result doRun(final BuildListener listener) throws Exception {
            try {
                listener.getLogger().println("Parsing POMs");
                // TODO: this needs to be moved to its own class since MavenModuleSet is not serializable
                List<PomInfo> poms = project.getWorkspace().act(new FileCallable<List<PomInfo>>() {
                    public List<PomInfo> invoke(File ws, VirtualChannel channel) throws IOException {
                        // TODO: this logic needs to be smarter
                        File pom = new File(ws,"pom.xml");

                        try {
                            MavenEmbedder embedder = MavenUtil.createEmbedder(listener);
                            MavenProject mp = embedder.readProject(pom);
                            Map<MavenProject,String> relPath = new HashMap<MavenProject,String>();
                            MavenUtil.resolveModules(embedder,mp,"",relPath);

                            List<PomInfo> infos = new ArrayList<PomInfo>();
                            toPomInfo(mp,relPath,infos);
                            return infos;
                        } catch (MavenEmbedderException e) {
                            // TODO: better error handling needed
                            throw new IOException2(e);
                        } catch (ProjectBuildingException e) {
                            throw new IOException2(e);
                        }
                    }

                    private void toPomInfo(MavenProject mp, Map<MavenProject,String> relPath, List<PomInfo> infos) {
                        infos.add(new PomInfo(mp,relPath.get(mp)));
                        for (MavenProject child : (List<MavenProject>)mp.getCollectedProjects())
                            toPomInfo(child,relPath,infos);
                    }
                });

                // update the module list
                Map<ModuleName,MavenModule> modules = project.modules;
                synchronized(modules) {
                    Map<ModuleName,MavenModule> old = new HashMap<ModuleName, MavenModule>(modules);

                    modules.clear();
                    project.setRootModule(poms.get(0).name);
                    for (PomInfo pom : poms) {
                        MavenModule mm = old.get(pom.name);
                        if(mm!=null) {// found an existing matching module
                            mm.reconfigure(pom);
                            modules.put(pom.name,mm);
                        } else {// this looks like a new module
                            mm = new MavenModule(project,pom);
                            modules.put(mm.getModuleName(),mm);
                        }
                        mm.save();
                    }

                    // remaining modules are no longer active.
                    old.keySet().removeAll(modules.keySet());
                    for (MavenModule om : old.values())
                        om.disable();
                    modules.putAll(old);
                }

                Hudson.getInstance().rebuildDependencyGraph();

                // start the build
                listener.getLogger().println("Triggering "+project.getRootModule().getModuleName());
                project.getRootModule().scheduleBuild();
                
                return null;
            } catch (IOException e) {
                e.printStackTrace(listener.error("Failed to parse POMs"));
                return Result.FAILURE;
            } catch (InterruptedException e) {
                e.printStackTrace(listener.error("Aborted"));
                return Result.FAILURE;
            } catch (RuntimeException e) {
                // bug in the code.
                e.printStackTrace(listener.error("Processing failed due to a bug in the code. Please report thus to users@hudson.dev.java.net"));
                throw e;
            }
        }

        public void post(BuildListener listener) {
        }
    }
}
