/*
 * Copyright 2012-2019 the original author or authors.
 *
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
 */

package io.spring.initializr.generator.spring.build.gradle;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import io.spring.initializr.generator.buildsystem.Dependency;
import io.spring.initializr.generator.buildsystem.DependencyScope;
import io.spring.initializr.generator.buildsystem.gradle.GradleKtsBuildSystem;
import io.spring.initializr.generator.language.java.JavaLanguage;
import io.spring.initializr.generator.packaging.war.WarPackaging;
import io.spring.initializr.generator.project.ProjectDescription;
import io.spring.initializr.generator.spring.build.BuildProjectGenerationConfiguration;
import io.spring.initializr.generator.spring.build.BuildWriter;
import io.spring.initializr.generator.spring.test.InitializrMetadataTestBuilder;
import io.spring.initializr.generator.test.project.ProjectAssetTester;
import io.spring.initializr.generator.test.project.ProjectStructure;
import io.spring.initializr.generator.version.Version;
import io.spring.initializr.metadata.InitializrMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GradleProjectGenerationConfiguration} with Kotlin DSL build system.
 *
 * @author Jean-Baptiste Nizet
 */
class GradleKtsProjectGenerationConfigurationTests {

	private ProjectAssetTester projectTester;

	@BeforeEach
	void setup(@TempDir Path directory) {
		this.projectTester = new ProjectAssetTester().withIndentingWriterFactory()
				.withConfiguration(BuildProjectGenerationConfiguration.class,
						GradleProjectGenerationConfiguration.class)
				.withDirectory(directory)
				.withBean(InitializrMetadata.class,
						() -> InitializrMetadataTestBuilder.withDefaults().build())
				.withDescriptionCustomizer((description) -> description
						.setBuildSystem(new GradleKtsBuildSystem()));
	}

	static Stream<Arguments> supportedPlatformVersions() {
		// previous versions use gradle < 5, where Kotlin DSL is not supported
		return Stream.of(Arguments.arguments("2.1.3.RELEASE"));
	}

	@ParameterizedTest(name = "Spring Boot {0}")
	@MethodSource("supportedPlatformVersions")
	void buildWriterIsContributed(String platformVersion) {
		ProjectDescription description = new ProjectDescription();
		description.setPlatformVersion(Version.parse(platformVersion));
		description.setLanguage(new JavaLanguage());
		BuildWriter buildWriter = this.projectTester.generate(description,
				(context) -> context.getBean(BuildWriter.class));
		assertThat(buildWriter)
				.isInstanceOf(KotlinDslGradleBuildProjectContributor.class);
	}

	static Stream<Arguments> gradleWrapperParameters() {
		return Stream.of(Arguments.arguments("2.1.3.RELEASE", "5.2.1"));
	}

	@ParameterizedTest(name = "Spring Boot {0}")
	@MethodSource("gradleWrapperParameters")
	void gradleWrapperIsContributedWhenGeneratingGradleKtsProject(String platformVersion,
			String expectedGradleVersion) throws IOException {
		ProjectDescription description = new ProjectDescription();
		description.setPlatformVersion(Version.parse(platformVersion));
		description.setLanguage(new JavaLanguage());
		ProjectStructure projectStructure = this.projectTester.generate(description);
		List<String> relativePaths = projectStructure.getRelativePathsOfProjectFiles();
		assertThat(relativePaths).contains("gradlew", "gradlew.bat",
				"gradle/wrapper/gradle-wrapper.properties",
				"gradle/wrapper/gradle-wrapper.jar");
		try (Stream<String> lines = Files.lines(
				projectStructure.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
			assertThat(lines.filter((line) -> line
					.contains(String.format("gradle-%s-bin.zip", expectedGradleVersion))))
							.hasSize(1);
		}
	}

	@Test
	void buildDotGradleDotKtsIsContributedWhenGeneratingGradleKtsProject()
			throws IOException {
		ProjectDescription description = new ProjectDescription();
		description.setPlatformVersion(Version.parse("2.1.0.RELEASE"));
		description.setLanguage(new JavaLanguage("11"));
		description.addDependency("acme",
				new Dependency("com.example", "acme", DependencyScope.COMPILE));
		ProjectStructure projectStructure = this.projectTester.generate(description);
		List<String> relativePaths = projectStructure.getRelativePathsOfProjectFiles();
		assertThat(relativePaths).contains("build.gradle.kts");
		Path path = projectStructure.resolve("build.gradle.kts");
		String[] lines = readAllLines(path);
		assertThat(lines).containsExactly("plugins {",
				"    id(\"org.springframework.boot\") version \"2.1.0.RELEASE\"",
				"    id(\"io.spring.dependency-management\") version \"1.0.6.RELEASE\"",
				"    java", "}", "", "group = \"com.example\"",
				"version = \"0.0.1-SNAPSHOT\"",
				"java.sourceCompatibility = JavaVersion.VERSION_11", "", "repositories {",
				"    mavenCentral()", "}", "", "dependencies {",
				"    implementation(\"org.springframework.boot:spring-boot-starter\")",
				"    implementation(\"com.example:acme\")",
				"    testImplementation(\"org.springframework.boot:spring-boot-starter-test\")",
				"}");
	}

	@Test
	void warPluginIsAppliedWhenBuildingProjectThatUsesWarPackaging() throws IOException {
		ProjectDescription description = new ProjectDescription();
		description.setPlatformVersion(Version.parse("2.1.0.RELEASE"));
		description.setLanguage(new JavaLanguage());
		description.setPackaging(new WarPackaging());
		ProjectStructure projectStructure = this.projectTester.generate(description);
		List<String> relativePaths = projectStructure.getRelativePathsOfProjectFiles();
		assertThat(relativePaths).contains("build.gradle.kts");
		try (Stream<String> lines = Files
				.lines(projectStructure.resolve("build.gradle.kts"))) {
			assertThat(lines.filter((line) -> line.contains("    war"))).hasSize(1);
		}
	}

	private static String[] readAllLines(Path file) throws IOException {
		String content = StreamUtils.copyToString(
				new FileInputStream(new File(file.toString())), StandardCharsets.UTF_8);
		String[] lines = content.split("\\r?\\n");
		assertThat(content).endsWith(System.lineSeparator());
		return lines;
	}

}
