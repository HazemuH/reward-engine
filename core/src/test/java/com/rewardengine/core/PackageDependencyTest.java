package com.rewardengine.core;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Feature packages may depend on each other in one direction only. counter
 * depends on campaign (the consuming UPDATE checks campaign status); campaign
 * reaches counter-owned data only through interfaces it defines itself.
 */
@AnalyzeClasses(packages = "com.rewardengine.core", importOptions = ImportOption.DoNotIncludeTests.class)
class PackageDependencyTest {

	@ArchTest
	static final ArchRule campaignDoesNotDependOnCounter = noClasses()
			.that().resideInAPackage("..core.campaign..")
			.should().dependOnClassesThat().resideInAPackage("..core.counter..");
}
