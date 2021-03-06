/**
 *
 */
package io.quarkus.bootstrap.workspace.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.bootstrap.BootstrapException;
import io.quarkus.bootstrap.model.AppArtifactKey;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalProject;
import io.quarkus.bootstrap.resolver.maven.workspace.LocalWorkspace;
import io.quarkus.bootstrap.util.IoUtils;
import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Parent;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class LocalWorkspaceDiscoveryTest {

    private static Dependency newDependency(String artifactId) {
        return newDependency(MvnProjectBuilder.DEFAULT_GROUP_ID, artifactId, MvnProjectBuilder.DEFAULT_VERSION);
    }

    private static Dependency newDependency(String groupId, String artifactId, String version) {
        final Dependency dep = new Dependency();
        dep.setGroupId(groupId);
        dep.setArtifactId(artifactId);
        dep.setVersion(version);
        return dep;
    }

    protected static Path workDir;

    private static Properties systemPropertiesBackup;

    @BeforeAll
    public static void setup() throws Exception {
        workDir = IoUtils.createRandomTmpDir();

        systemPropertiesBackup = (Properties) System.getProperties().clone();

        final Parent parent = new Parent();
        parent.setGroupId(MvnProjectBuilder.DEFAULT_GROUP_ID);
        parent.setArtifactId("parent");
        parent.setVersion(MvnProjectBuilder.DEFAULT_VERSION);
        parent.setRelativePath(null);

        final Parent parentWithEmptyRelativePath = new Parent();
        parent.setGroupId(MvnProjectBuilder.DEFAULT_GROUP_ID);
        parent.setArtifactId("parent-empty-path");
        parent.setVersion(MvnProjectBuilder.DEFAULT_VERSION);
        parent.setRelativePath("");

        MvnProjectBuilder.forArtifact("root")
                .setParent(parent)

                .addModule("module1", "root-no-parent-module", false)
                .addDependency(newDependency("root-module-not-direct-child"))
                .getParent()

                .addModule("module2", "root-module-with-parent", true)
                .addDependency(newDependency("root-no-parent-module"))
                .addDependency(newDependency("external-dep"))
                .addDependency(newDependency(LocalProject.PROJECT_GROUPID, "root-module-not-direct-child",
                        MvnProjectBuilder.DEFAULT_VERSION))
                .getParent()

                .addModule("other/module3", "root-module-not-direct-child", true)
                .getParent()

                .addModule("module4", "empty-parent-relative-path-module").setParent(parentWithEmptyRelativePath)
                .getParent()

                .build(workDir.resolve("root"));

        final Parent rootParent = new Parent();
        rootParent.setGroupId(MvnProjectBuilder.DEFAULT_GROUP_ID);
        rootParent.setArtifactId("root");
        rootParent.setVersion(MvnProjectBuilder.DEFAULT_VERSION);
        rootParent.setRelativePath(null);

        MvnProjectBuilder.forArtifact("non-module-child")
                .setParent(rootParent)
                .addModule("module1", "another-child", true)
                .getParent()
                .build(workDir.resolve("root").resolve("non-module-child"));

        // independent project in the tree
        MvnProjectBuilder.forArtifact("independent")
                .addDependency(newDependency("root-module-with-parent"))
                .build(workDir.resolve("root").resolve("independent"));
    }

    @AfterEach
    public void restoreSystemProperties() {
        if (systemPropertiesBackup != null) {
            System.setProperties(systemPropertiesBackup);
        }
    }

    @AfterAll
    public static void cleanup() {
        IoUtils.recursiveDelete(workDir);
    }

    @Test
    public void loadIndependentProjectInTheWorkspaceTree() throws Exception {
        final LocalProject project = LocalProject
                .loadWorkspace(workDir.resolve("root").resolve("independent").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("independent", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());
        final Map<AppArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(6, projects.size());
        assertTrue(projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "independent")));

        assertLocalDeps(project, "root-module-not-direct-child", "root-no-parent-module", "root-module-with-parent");
    }

    @Test
    public void loadModuleProjectWithoutParent() throws Exception {
        final LocalProject project = LocalProject
                .load(workDir.resolve("root").resolve("module1").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-no-parent-module", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());
        assertLocalDeps(project);
    }

    @Test
    public void loadWorkspaceForModuleWithoutParent() throws Exception {
        final LocalProject project = LocalProject
                .loadWorkspace(workDir.resolve("root").resolve("module1").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-no-parent-module", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());
        assertNotNull(project.getWorkspace());
        final Map<AppArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(5, projects.size());
        assertTrue(projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-no-parent-module")));
        assertLocalDeps(project, "root-module-not-direct-child");
    }

    @Test
    public void loadModuleProjectWithParent() throws Exception {
        final LocalProject project = LocalProject
                .load(workDir.resolve("root").resolve("module2").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-module-with-parent", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());
        assertLocalDeps(project);
    }

    @Test
    public void loadWorkspaceForModuleWithParent() throws Exception {
        final LocalProject project = LocalProject
                .loadWorkspace(workDir.resolve("root").resolve("module2").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-module-with-parent", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());

        assertCompleteWorkspace(project);
        assertLocalDeps(project, "root-module-not-direct-child", "root-no-parent-module");
    }

    @Test
    public void loadWorkspaceForModuleWithNotDirectParentPath() throws Exception {
        final LocalProject project = LocalProject.loadWorkspace(
                workDir.resolve("root").resolve("other").resolve("module3").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("root-module-not-direct-child", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());

        assertCompleteWorkspace(project);
        assertLocalDeps(project);
    }

    @Test
    public void loadNonModuleChildProject() throws Exception {
        final LocalProject project = LocalProject
                .loadWorkspace(workDir.resolve("root").resolve("non-module-child").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals("non-module-child", project.getArtifactId());
        final Map<AppArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(7, projects.size());
        assertTrue(projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-no-parent-module")));
        assertTrue(projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-with-parent")));
        assertTrue(
                projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-not-direct-child")));
        assertTrue(projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root")));
        assertTrue(projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "non-module-child")));
        assertTrue(projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "another-child")));
        assertTrue(projects
                .containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "empty-parent-relative-path-module")));
        assertLocalDeps(project, "another-child");
    }

    /**
     * Empty relativePath is a hack sometimes used to always resolve parent from repository and skip default "../" lookup
     */
    @Test
    public void loadWorkspaceForModuleWithEmptyRelativePathParent() throws Exception {
        final LocalProject project = LocalProject.loadWorkspace(
                workDir.resolve("root").resolve("module4").resolve("target").resolve("classes"));
        assertNotNull(project);
        assertNotNull(project.getWorkspace());
        assertEquals(MvnProjectBuilder.DEFAULT_GROUP_ID, project.getGroupId());
        assertEquals("empty-parent-relative-path-module", project.getArtifactId());
        assertEquals(MvnProjectBuilder.DEFAULT_VERSION, project.getVersion());

        assertCompleteWorkspace(project);
        assertLocalDeps(project);
    }

    @Test
    public void testVersionRevisionProperty() throws Exception {
        testMavenCiFriendlyVersion("${revision}", "workspace-revision", "1.2.3", true);
    }

    @Test
    public void testVersionRevisionPropertyOverridenWithSystemProperty() throws Exception {
        final String expectedResolvedVersion = "build123";
        System.setProperty("revision", expectedResolvedVersion);

        testMavenCiFriendlyVersion("${revision}", "workspace-revision", expectedResolvedVersion, false);
    }

    @Test
    public void testVersionSha1Property() throws Exception {
        testMavenCiFriendlyVersion("${sha1}", "workspace-sha1", "1.2.3", true);
    }

    @Test
    public void testVersionSha1PropertyOverridenWithSystemProperty() throws Exception {
        final String expectedResolvedVersion = "build123";
        System.setProperty("sha1", expectedResolvedVersion);

        testMavenCiFriendlyVersion("${sha1}", "workspace-sha1", expectedResolvedVersion, false);
    }

    @Test
    public void testVersionChangelistProperty() throws Exception {
        testMavenCiFriendlyVersion("${changelist}", "workspace-changelist", "1.2.3", true);
    }

    @Test
    public void testVersionChangelistPropertyOverridenWithSystemProperty() throws Exception {
        final String expectedResolvedVersion = "build123";
        System.setProperty("changelist", expectedResolvedVersion);

        testMavenCiFriendlyVersion("${changelist}", "workspace-changelist", expectedResolvedVersion, false);
    }

    @Test
    public void testVersionMultipleProperties() throws Exception {
        testMavenCiFriendlyVersion("${revision}${sha1}${changelist}", "workspace-multiple", "1.2.3", true);
    }

    @Test
    public void testVersionMultiplePropertiesOverridenWithSystemProperty() throws Exception {
        final String expectedResolvedVersion = "build123";
        System.setProperty("revision", "build");
        System.setProperty("sha1", "12");
        System.setProperty("changelist", "3");

        testMavenCiFriendlyVersion("${revision}${sha1}${changelist}", "workspace-multiple", expectedResolvedVersion, false);
    }

    private void testMavenCiFriendlyVersion(String placeholder, String testResourceDirName, String expectedResolvedVersion,
            boolean resolvesFromWorkspace) throws BootstrapException, URISyntaxException {
        final URL module1Url = Thread.currentThread().getContextClassLoader()
                .getResource(testResourceDirName + "/root/module1");
        assertNotNull(module1Url);
        final Path module1Dir = Paths.get(module1Url.toURI());
        assertTrue(Files.exists(module1Dir));

        final LocalProject module1 = LocalProject.load(module1Dir);

        assertEquals(expectedResolvedVersion, module1.getVersion());
        if (resolvesFromWorkspace) {
            assertNotNull(module1.getWorkspace()); // the property must have been resolved from the workspace
        } else {
            assertNull(module1.getWorkspace()); // the workspace was not necessary to resolve the property
        }

        final LocalWorkspace localWorkspace = resolvesFromWorkspace ? module1.getWorkspace()
                : LocalProject.loadWorkspace(module1Dir).getWorkspace();
        final File root = localWorkspace
                .findArtifact(new DefaultArtifact(module1.getGroupId(), "root", null, "pom", placeholder));
        assertNotNull(root);
        assertTrue(root.exists());
        final URL rootPomUrl = Thread.currentThread().getContextClassLoader()
                .getResource(testResourceDirName + "/root/pom.xml");
        assertEquals(new File(rootPomUrl.toURI()), root);
    }

    private void assertCompleteWorkspace(final LocalProject project) {
        final Map<AppArtifactKey, LocalProject> projects = project.getWorkspace().getProjects();
        assertEquals(5, projects.size());
        assertTrue(projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-no-parent-module")));
        assertTrue(projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-with-parent")));
        assertTrue(
                projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root-module-not-direct-child")));
        assertTrue(projects
                .containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "empty-parent-relative-path-module")));
        assertTrue(projects.containsKey(new AppArtifactKey(MvnProjectBuilder.DEFAULT_GROUP_ID, "root")));
    }

    private static void assertLocalDeps(LocalProject project, String... deps) {
        final List<LocalProject> list = project.getSelfWithLocalDeps();
        assertEquals(deps.length + 1, list.size());
        int i = 0;
        while (i < deps.length) {
            final LocalProject dep = list.get(i);
            assertEquals(deps[i++], dep.getArtifactId());
            assertEquals(project.getGroupId(), dep.getGroupId());
        }
        final LocalProject self = list.get(i);
        assertEquals(project.getGroupId(), self.getGroupId());
        assertEquals(project.getArtifactId(), self.getArtifactId());
        assertEquals(project.getVersion(), self.getVersion());
    }
}
