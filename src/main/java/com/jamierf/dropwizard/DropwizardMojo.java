package com.jamierf.dropwizard;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import com.jamierf.dropwizard.config.*;
import com.jamierf.dropwizard.filter.DependencyFilter;
import com.jamierf.dropwizard.resource.EmbeddedResource;
import com.jamierf.dropwizard.resource.FileResource;
import com.jamierf.dropwizard.resource.Resource;
import com.jamierf.dropwizard.util.LogConsole;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.tools.tar.TarEntry;
import org.codehaus.plexus.util.FileUtils;
import org.vafer.jdeb.Console;
import org.vafer.jdeb.PackagingException;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

@Mojo(name = "dwpackage", defaultPhase = LifecyclePhase.PACKAGE)
public class DropwizardMojo extends AbstractMojo {

    private static final String INPUT_ARTIFACT_TYPE = "jar";
    private static final String OUTPUT_ARTIFACT_TYPE = "deb";

    private static final String DROPWIZARD_GROUP_ID = "io.dropwizard";
    private static final String DROPWIZARD_ARTIFACT_ID = "dropwizard-core";

    private static final String WORKING_DIRECTORY_NAME = "dropwizard-deb-package";

    @SuppressWarnings("OctalInteger")
    private static final int UNIX_MODE_USER_ONLY = 0100600;

    @Component
    private final MavenProjectHelper helper = null;

    @Component
    private final MavenProject project = null;

    @Component
    private final MavenSession session = null;
    
    @Parameter
    private final DebConfiguration deb = new DebConfiguration();

    @Parameter
    private final JvmConfiguration jvm = new JvmConfiguration();

    @Parameter
    private final UnixConfiguration unix = new UnixConfiguration();

    @Parameter
    private final PathConfiguration path = new PathConfiguration();

    @Parameter
    private final Map<String, String> dropwizard = Collections.emptyMap();

    @Parameter(required = true)
    private final File configTemplate = null;

    @Parameter
    private File artifactFile;

    @Parameter
    private File outputFile;

    @Parameter
    private final PgpConfiguration pgp = null;

    @Parameter
    @SuppressWarnings("FieldCanBeLocal")
    private boolean validate = true;

    private final Console log = new LogConsole(getLog());

    public void execute() throws MojoExecutionException {
        setupMojoConfiguration();

        final Collection<Resource> resources = buildResourceList();
        final Map<String, Object> parameters = buildParameterMap();

        final File resourcesDir = extractResources(resources, parameters);

        if (validate) {
            validateApplicationConfiguration(resourcesDir);
        }

        final File debFile = createPackage(resources, resourcesDir);
        attachArtifact(debFile);
    }

    private void setupMojoConfiguration() throws MojoExecutionException {
        deb.setProject(project);
        deb.setSession(session);
        path.setProject(project);

        if (artifactFile == null) {
            final Artifact artifact = project.getArtifact();
            if (!INPUT_ARTIFACT_TYPE.equals(artifact.getType())) {
                throw new MojoExecutionException(String.format("Artifact type %s not recognised, required %s",
                        artifact.getType(), INPUT_ARTIFACT_TYPE));
            }

            artifactFile = artifact.getFile();
        }

        if (outputFile == null) {
            final String outputFilename = String.format("%s-%s.%s", project.getArtifactId(), project.getVersion(), OUTPUT_ARTIFACT_TYPE);
            outputFile = new File(project.getBuild().getDirectory(), outputFilename);
        }
    }

    // TODO: Allow the user to add to this
    private Collection<Resource> buildResourceList() {
        return ImmutableList.<Resource>builder()
                .add(new FileResource(configTemplate, true, path.getConfigFile(), unix.getUser(), unix.getUser(), UNIX_MODE_USER_ONLY))
                .add(new EmbeddedResource("/files/upstart.conf", true, path.getUpstartFile(), "root", "root", TarEntry.DEFAULT_FILE_MODE))
                .add(new FileResource(artifactFile, false, path.getJarFile(), unix.getUser(), unix.getUser(), TarEntry.DEFAULT_FILE_MODE))
                .build();
    }

    private Map<String, Object> buildParameterMap() {
        return ImmutableMap.<String, Object>builder()
                .put("project", project)
                .put("session", session)
                .put("deb", deb)
                .put("jvm", jvm)
                .put("unix", unix)
                .put("dw", dropwizard)
                .put("dropwizard", dropwizard)
                .put("path", path)
                .build();
    }

    private File extractResources(final Collection<Resource> resources, final Map<String, Object> parameters) throws MojoExecutionException {
        try {
            final File outputDir = new File(project.getBuild().getDirectory(), WORKING_DIRECTORY_NAME);
            new ResourceExtractor(parameters, getLog()).extractResources(resources, outputDir);
            return outputDir;
        }
        catch (IOException e) {
            throw new MojoExecutionException("Failed to extract resources", e);
        }
    }

    private void validateApplicationConfiguration(final File resourcesDir) throws MojoExecutionException {
        try {
            final Optional<Dependency> dropwizardDependency = Iterables.tryFind(project.getModel().getDependencies(),
                    new DependencyFilter(DROPWIZARD_GROUP_ID, DROPWIZARD_ARTIFACT_ID));
            if (!dropwizardDependency.isPresent()) {
                log.warn("Failed to find Dropwizard dependency in project. Skipping configuration validation.");
                return;
            }

            final ComparableVersion version = new ComparableVersion(dropwizardDependency.get().getVersion());
            log.info(String.format("Detected Dropwizard %s, attempting to validate configuration.", version));

            if (!ApplicationValidator.canSupportVersion(version)) {
                log.warn(String.format("The latest Dropwizard version supported by this plugin is %s." +
                                "We will attempt validation anyway, but if it fails you can disable this" +
                                "step by setting `validation` to false.",
                        ApplicationValidator.MAX_SUPPORTED_VERSION));
            }

            final File tempDirectory = Files.createTempDir();
            try {
                final File configFile = new File(resourcesDir, "/files" + path.getConfigFile());
                final ApplicationValidator validator = new ApplicationValidator(artifactFile, log, tempDirectory);
                validator.validateConfiguration(configFile);
            }
            finally {
                FileUtils.forceDelete(tempDirectory);
            }
        }
        catch (IOException | IllegalArgumentException | ClassNotFoundException e) {
            throw new MojoExecutionException("Failed to validate configuration", e);
        }
    }

    private File createPackage(final Collection<Resource> resources, final File inputDir) throws MojoExecutionException {
        try {
            new PackageBuilder(project, log, Optional.fromNullable(pgp)).createPackage(resources, inputDir, outputFile);
            return outputFile;
        }
        catch (PackagingException e) {
            throw new MojoExecutionException("Failed to create Debian package", e);
        }
    }

    private void attachArtifact(final File artifactFile) {
        log.info(String.format("Attaching created %s package %s", OUTPUT_ARTIFACT_TYPE, artifactFile));
        helper.attachArtifact(project, OUTPUT_ARTIFACT_TYPE, artifactFile);
    }
}
