/*
 * Copyright 2023 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.gradle;

import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.initialization.Settings;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.invocation.DefaultGradle;
import org.gradle.util.GradleVersion;
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet;
import org.openrewrite.gradle.marker.GradleProjectBuilder;
import org.openrewrite.gradle.marker.GradleSettings;
import org.openrewrite.gradle.marker.GradleSettingsBuilder;
import org.openrewrite.gradle.toolingapi.*;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toMap;

public interface ProjectInfo {

    GradleSettings getGradleSettings();

    String getGradleVersion();

    boolean isRootProject();

    File getRootProjectDir();

    Collection<ProjectInfo> getSubprojects();

    File getProjectDir();

    File getBuildDir();

    File getBuildscriptFile();

    File getSettingsBuildscriptFile();

    Map<String, ?> getProperties();

    Collection<SourceSetInfo> getSourceSets();

    <T> T getExtensionByType(Class<T> extClass);

    GradleProject getGradleProjectToolingModel();

    boolean isMultiPlatformKotlinProject();

    Collection<KotlinSourceSetInfo> getKotlinSourceSets();

    Collection<File> getBuildscriptClasspath();

    Collection<File> getSettingsClasspath();

    interface SourceSetInfo {

        String getName();

        Collection<File> getSources();

        Collection<File> getSourceDirectories();

        Collection<File> getJava();

        Collection<File> getClassesDirs();

        Collection<File> getCompileClasspath();

        Collection<File> getImplementationClasspath();

        JavaVersionInfo getJavaVersionInfo();

    }

    interface KotlinSourceSetInfo {
        String getName();

        Collection<File> getKotlin();

        Collection<File> getCompileClasspath();

        Collection<File> getImplementationClasspath();
    }

    static ProjectInfo from(Project project) {
        return new ProjectInfo() {

            private MappingToToolingModel.GradleProjectImpl gradleProjectImpl;

            private final Logger logger = Logging.getLogger(ProjectInfo.class);

            @Override
            public GradleSettings getGradleSettings() {
                return GradleSettingsBuilder.gradleSettings(((DefaultGradle) project.getGradle()).getSettings());
            }

            @Override
            public String getGradleVersion() {
                return project.getGradle().getGradleVersion();
            }

            @Override
            public boolean isRootProject() {
                return project == project.getRootProject();
            }

            @Override
            public File getRootProjectDir() {
                return project.getRootProject().getProjectDir();
            }

            @Override
            public Collection<ProjectInfo> getSubprojects() {
                List<ProjectInfo> sub = new ArrayList<>(project.getSubprojects().size());
                for (Project s : project.getSubprojects()) {
                    sub.add(from(s));
                }
                return sub;
            }

            @Override
            public File getProjectDir() {
                return project.getProjectDir();
            }

            @Override
            public File getBuildDir() {
                return project.getBuildDir();
            }

            @Override
            public File getBuildscriptFile() {
                return project.getBuildscript().getSourceFile();
            }

            @Override
            public File getSettingsBuildscriptFile() {
                Settings settings = ((DefaultGradle) project.getGradle()).getSettings();
                return settings.getBuildscript().getSourceFile() == null ? null : settings.getBuildscript().getSourceFile();
            }

            @Override
            public Map<String, ?> getProperties() {
                return project.getProperties();
            }

            @Override
            public List<SourceSetInfo> getSourceSets() {
                JavaPluginConvention javaConvention = project.getConvention().findPlugin(JavaPluginConvention.class);
                if (javaConvention != null) {
                    List<SourceSetInfo> infos = new ArrayList<>(javaConvention.getSourceSets().size());
                    for (SourceSet sourceSet : javaConvention.getSourceSets()) {
                        infos.add(new SourceSetInfo() {
                            @Override
                            public String getName() {
                                return sourceSet.getName();
                            }

                            @Override
                            public Collection<File> getSources() {
                                return sourceSet.getAllSource().getFiles();
                            }

                            @Override
                            public Collection<File> getSourceDirectories() {
                                return sourceSet.getResources().getSourceDirectories().getFiles();
                            }

                            @Override
                            public Collection<File> getJava() {
                                return sourceSet.getAllJava().getFiles();
                            }

                            @Override
                            public Collection<File> getClassesDirs() {
                                return sourceSet.getOutput().getClassesDirs().getFiles();
                            }

                            @Override
                            public Collection<File> getCompileClasspath() {
                                return sourceSet.getCompileClasspath().getFiles();
                            }

                            @Override
                            public Collection<File> getImplementationClasspath() {
                                // classpath doesn't include the transitive dependencies of the implementation configuration
                                // These aren't needed for compilation, but we want them so recipes have access to comprehensive type information
                                // The implementation configuration isn't resolvable, so we need a new configuration that extends from it
                                Configuration implementation = project.getConfigurations().getByName(sourceSet.getImplementationConfigurationName());
                                Configuration rewriteImplementation = project.getConfigurations().maybeCreate("rewrite" + sourceSet.getImplementationConfigurationName());
                                rewriteImplementation.extendsFrom(implementation);

                                Set<File> implementationClasspath;
                                try {
                                    return rewriteImplementation.resolve();
                                } catch (Exception e) {
                                    logger.warn("Failed to resolve dependencies from {}:{}. Some type information may be incomplete",
                                            project.getPath(), sourceSet.getImplementationConfigurationName());
                                    return emptySet();
                                }
                            }

                            @Override
                            public JavaVersionInfo getJavaVersionInfo() {
                                JavaCompile javaCompileTask = (JavaCompile) project.getTasks().getByName(sourceSet.getCompileJavaTaskName());
                                return new JavaVersionInfo() {
                                    @Override
                                    public String getCreatedBy() {
                                        return System.getProperty("java.runtime.version");
                                    }

                                    @Override
                                    public String getVmVendor() {
                                        return System.getProperty("java.vm.vendor");
                                    }

                                    @Override
                                    public String getSourceCompatibility() {
                                        return javaCompileTask.getSourceCompatibility();
                                    }

                                    @Override
                                    public String getTargetCompatibility() {
                                        return javaCompileTask.getTargetCompatibility();
                                    }
                                };
                            }

                        });
                    }
                    return infos;
                }
                return Collections.emptyList();
            }

            @Override
            public <T> T getExtensionByType(Class<T> extClass) {
                return project.getExtensions().findByType(extClass);
            }

            @Override
            public GradleProject getGradleProjectToolingModel() {
                if (gradleProjectImpl == null) {
                    org.openrewrite.gradle.marker.GradleProject marker = GradleProjectBuilder.gradleProject(project);
                    gradleProjectImpl = new MappingToToolingModel.GradleProjectImpl(
                            marker.getName(),
                            marker.getPath(),
                            marker.getGroup(),
                            marker.getVersion(),
                            marker.getPlugins().stream().map(MappingToToolingModel::toToolingModel).collect(Collectors.toList()),
                            marker.getMavenRepositories().stream().map(MappingToToolingModel::toToolingModel).collect(Collectors.toList()),
                            marker.getMavenPluginRepositories().stream().map(MappingToToolingModel::toToolingModel).collect(Collectors.toList()),
                            MappingToToolingModel.toToolingModel(marker.getNameToConfiguration())
                    );
                }
                return gradleProjectImpl;
            }

            @Override
            public boolean isMultiPlatformKotlinProject() {
                try {
                    return project.getPlugins().hasPlugin("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension") ||
                            project.getExtensions().findByName("kotlin") != null && project.getExtensions().findByName("kotlin").getClass().getCanonicalName().startsWith("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension");
                } catch (Throwable t) {
                    logger.warn(t.getMessage());
                    return false;
                }
            }

            @Override
            public Collection<KotlinSourceSetInfo> getKotlinSourceSets() {
                NamedDomainObjectContainer<KotlinSourceSet> sourceSets;
                try {
                    Object kotlinExtension = project.getExtensions().getByName("kotlin");
                    Class<?> clazz = kotlinExtension.getClass().getClassLoader().loadClass("org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension");
                    //noinspection unchecked
                    sourceSets = (NamedDomainObjectContainer<KotlinSourceSet>) clazz.getMethod("getSourceSets")
                            .invoke(kotlinExtension);

                } catch (Exception e) {
                    logger.warn("Failed to resolve KotlinMultiplatformExtension from {}. No sources files from KotlinMultiplatformExtension will be parsed.",
                            project.getPath());
                    return Collections.emptyList();
                }

                SortedSet<String> sourceSetNames;
                try {
                    //noinspection unchecked
                    sourceSetNames = (SortedSet<String>) sourceSets.getClass().getMethod("getNames")
                            .invoke(sourceSets);
                } catch (Exception e) {
                    logger.warn("Failed to resolve SourceSetNames in KotlinMultiplatformExtension from {}. No sources files from KotlinMultiplatformExtension will be parsed.",
                            project.getPath());
                    return Collections.emptyList();
                }

                List<KotlinSourceSetInfo> infos = new ArrayList<>(sourceSetNames.size());
                for (String sourceSetName : sourceSetNames) {
                    try {
                        Object sourceSet = sourceSets.getClass().getMethod("getByName", String.class)
                                .invoke(sourceSets, sourceSetName);
                        SourceDirectorySet kotlinDirectorySet = (SourceDirectorySet) sourceSet.getClass().getMethod("getKotlin").invoke(sourceSet);

                        // classpath doesn't include the transitive dependencies of the implementation configuration
                        // These aren't needed for compilation, but we want them so recipes have access to comprehensive type information
                        // The implementation configuration isn't resolvable, so we need a new configuration that extends from it
                        String implementationName = (String) sourceSet.getClass().getMethod("getImplementationConfigurationName").invoke(sourceSet);
                        Configuration implementation = project.getConfigurations().getByName(implementationName);
                        Configuration rewriteImplementation = project.getConfigurations().maybeCreate("rewrite" + implementationName);
                        rewriteImplementation.extendsFrom(implementation);

                        Set<File> implementationClasspath;
                        try {
                            implementationClasspath = rewriteImplementation.resolve();
                        } catch (Exception e) {
                            logger.warn("Failed to resolve dependencies from {}:{}. Some type information may be incomplete",
                                    project.getPath(), implementationName);
                            implementationClasspath = emptySet();
                        }

                        String compileName = (String) sourceSet.getClass().getMethod("getCompileOnlyConfigurationName").invoke(sourceSet);
                        Configuration compileOnly = project.getConfigurations().getByName(compileName);
                        Configuration rewriteCompileOnly = project.getConfigurations().maybeCreate("rewrite" + compileName);
                        rewriteCompileOnly.setCanBeResolved(true);
                        rewriteCompileOnly.extendsFrom(compileOnly);

                        final Set<File> implClasspath = implementationClasspath;
                        final Set<File> compClasspath = rewriteCompileOnly.getFiles();

                        infos.add(new KotlinSourceSetInfo() {
                            @Override
                            public String getName() {
                                return sourceSetName;
                            }

                            @Override
                            public Collection<File> getKotlin() {
                                return kotlinDirectorySet.getFiles();
                            }

                            @Override
                            public Collection<File> getCompileClasspath() {
                                return compClasspath;
                            }

                            @Override
                            public Collection<File> getImplementationClasspath() {
                                return implClasspath;
                            }
                        });

                    } catch (Exception e) {
                        logger.warn("Failed to resolve sourceSet from {}:{}. Some type information may be incomplete",
                                project.getPath(), sourceSetName);
                    }
                }
                return infos;
            }

            @Override
            public Collection<File> getBuildscriptClasspath() {
                return project.getBuildscript().getConfigurations().getByName("classpath").resolve();
            }

            @Override
            public Collection<File> getSettingsClasspath() {
                if (GradleVersion.current().compareTo(GradleVersion.version("4.4")) >= 0) {
                    Settings settings = ((DefaultGradle) project.getGradle()).getSettings();
                    return settings.getBuildscript().getConfigurations().getByName("classpath").resolve();
                } else {
                    return Collections.emptyList();
                }
            }
        };
    }


}
