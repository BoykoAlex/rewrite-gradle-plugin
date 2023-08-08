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

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.Value;
import org.openrewrite.gradle.toolingapi.*;
import org.openrewrite.internal.lang.Nullable;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;

class MappingToToolingModel {
    static GroupArtifactImpl toToolingModel(org.openrewrite.maven.tree.GroupArtifact ga) {
        return ga == null ? null : new GroupArtifactImpl(ga.getGroupId(), ga.getArtifactId());
    }

    static GroupArtifactVersionImpl toToolingModel(org.openrewrite.maven.tree.GroupArtifactVersion gav) {
        return gav == null ? null : new GroupArtifactVersionImpl(gav.getGroupId(), gav.getArtifactId(), gav.getVersion());
    }

    static ResolvedGroupArtifactVersionImpl toToolingModel(org.openrewrite.maven.tree.ResolvedGroupArtifactVersion gav) {
        return gav == null ? null : new ResolvedGroupArtifactVersionImpl(gav.getGroupId(), gav.getArtifactId(), gav.getVersion(), gav.getDatedSnapshotVersion());
    }

    static ResolvedDependencyImpl toToolingModel(org.openrewrite.maven.tree.ResolvedDependency dep) {
        return dep == null ? null : new ResolvedDependencyImpl(
            toToolingModel(dep.getRepository()),
            toToolingModel(dep.getGav()),
            toToolingModel(dep.getRequested()),
            dep.getDependencies().stream().map(MappingToToolingModel::toToolingModel).collect(Collectors.toList()),
            dep.getDepth()
        );
    }

    static DependencyImpl toToolingModel(org.openrewrite.maven.tree.Dependency dep) {
        return dep == null ? null :new DependencyImpl(
            toToolingModel(dep.getGav()),
            dep.getClassifier(),
            dep.getType(),
            dep.getScope(),
            dep.getExclusions().stream().map(MappingToToolingModel::toToolingModel).collect(Collectors.toList()),
            dep.getOptional()
        );
    }

    static MavenRepositoryImpl toToolingModel(org.openrewrite.maven.tree.MavenRepository mr) {
        return mr == null ? null
            : new MavenRepositoryImpl(mr.getId(), mr.getUri(), mr.getReleases(), mr.getSnapshots(), mr.isKnownToExist(), mr.getUsername(), mr.getPassword(), mr.getDeriveMetadataIfMissing());
    }

    static GradlePluginDescriptorImpl toToolingModel(org.openrewrite.gradle.marker.GradlePluginDescriptor pd) {
        return pd == null ? null
            : new GradlePluginDescriptorImpl(pd.getFullyQualifiedClassName(), pd.getId());
    }

    static Map<String, GradleDependencyConfiguration> toToolingModel(Map<String, org.openrewrite.gradle.marker.GradleDependencyConfiguration> markerConfigs) {
        Map<String, GradleDependencyConfiguration> results = new HashMap<>();
        List<org.openrewrite.gradle.marker.GradleDependencyConfiguration> configurations = new ArrayList<>(markerConfigs.values());
        for (org.openrewrite.gradle.marker.GradleDependencyConfiguration toolingConfiguration : configurations) {
            GradleDependencyConfigurationImpl configuration = toToolingModel(toolingConfiguration);
            results.put(configuration.getName(), configuration);
        }

        // Record the relationships between dependency configurations
        for (org.openrewrite.gradle.marker.GradleDependencyConfiguration conf : configurations) {
            if (conf.getExtendsFrom().isEmpty()) {
                continue;
            }
            GradleDependencyConfiguration dc = results.get(conf.getName());
            if (dc != null) {
                List<org.openrewrite.gradle.toolingapi.GradleDependencyConfiguration> extendsFrom = conf.getExtendsFrom().stream()
                        .map(it -> results.get(it.getName()))
                        .collect(Collectors.toList());
                ((GradleDependencyConfigurationImpl)dc).setExtendsFrom(extendsFrom);
            }
        }
        return results;
    }

    static GradleDependencyConfigurationImpl toToolingModel(org.openrewrite.gradle.marker.GradleDependencyConfiguration c) {
        return new GradleDependencyConfigurationImpl(
                c.getName(),
                c.getDescription(),
                c.isTransitive(),
                c.isCanBeConsumed(),
                c.isCanBeResolved(),
                Collections.emptyList(),
                c.getRequested().stream().map(MappingToToolingModel::toToolingModel).collect(Collectors.toList()),
                c.getResolved().stream().map(MappingToToolingModel::toToolingModel).collect(Collectors.toList())
        );
    }
    @Getter
    @AllArgsConstructor
    static class GradleDependencyConfigurationImpl implements org.openrewrite.gradle.toolingapi.GradleDependencyConfiguration, Serializable {
        private String name;
        private String description;
        private boolean transitive;
        private boolean canBeConsumed;
        private boolean canBeResolved;
        @Setter
        private List<GradleDependencyConfiguration> extendsFrom;
        private List<Dependency> requested;
        private List<ResolvedDependency> resolved;
    }

    @Value
    @AllArgsConstructor
    static class GradlePluginDescriptorImpl implements org.openrewrite.gradle.toolingapi.GradlePluginDescriptor, Serializable {
        private String fullyQualifiedClassName;
        private String id;
    }

    @Value
    @AllArgsConstructor
    static class MavenRepositoryImpl implements org.openrewrite.gradle.toolingapi.MavenRepository, Serializable {
        private String id;
        private String uri;
        private @Nullable String releases;
        private @Nullable String snapshots;
        private boolean knownToExist;
        private @Nullable String username;
        private @Nullable String password;
        private @Nullable Boolean deriveMetadataIfMissing;
    }

    @Value
    @AllArgsConstructor
    static class DependencyImpl implements org.openrewrite.gradle.toolingapi.Dependency, Serializable {
        private GroupArtifactVersion gav;
        private @Nullable String classifier;
        private @Nullable String type;
        private String scope;
        private List<GroupArtifact> exclusions;
        private @Nullable String optional;
    }

    @AllArgsConstructor
    @Value
    static class GroupArtifactImpl implements org.openrewrite.gradle.toolingapi.GroupArtifact, Serializable {
        private String groupId;
        private String artifactId;
    }

    @Value
    @AllArgsConstructor
    static class GroupArtifactVersionImpl implements org.openrewrite.gradle.toolingapi.GroupArtifactVersion, Serializable {
        private String groupId;
        private String artifactId;
        private String version;
    }

    @Value
    @AllArgsConstructor
    static class ResolvedGroupArtifactVersionImpl implements org.openrewrite.gradle.toolingapi.ResolvedGroupArtifactVersion, Serializable {
        private String groupId;
        private String artifactId;
        private String version;
        private @Nullable String datedSnapshotVersion;
    }

    @Value
    @AllArgsConstructor
    static class ResolvedDependencyImpl implements org.openrewrite.gradle.toolingapi.ResolvedDependency, Serializable {
        private @Nullable MavenRepository repository;
        private ResolvedGroupArtifactVersion gav;
        private Dependency requested;
        private List<ResolvedDependency> dependencies;
        private int depth;
    }

    @Value
    @AllArgsConstructor
    static class GradleProjectImpl implements GradleProject, Serializable {
        private String name;
        private String path;
        private String group;
        private String version;
        private List<GradlePluginDescriptor> plugins;
        private List<MavenRepository> mavenRepositories;
        private List<MavenRepository> mavenPluginRepositories;
        private Map<String, GradleDependencyConfiguration> nameToConfiguration;
    }

}
