/*
 * Sonar Pitest Plugin
 * Copyright (C) 2009 Alexandre Victoor
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.plugins.pitest;

import static org.sonar.plugins.pitest.PitestConstants.MODE_KEY;
import static org.sonar.plugins.pitest.PitestConstants.MODE_SKIP;
import static org.sonar.plugins.pitest.PitestConstants.REPORT_DIRECTORY_KEY;
import static org.sonar.plugins.pitest.PitestConstants.REPOSITORY_KEY;

import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.issue.Issuable;
import org.sonar.api.issue.Issue;
import org.sonar.api.measures.Measure;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.JavaFile;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rules.ActiveRule;
import org.sonar.api.rules.Rule;
import org.sonar.api.scan.filesystem.FileQuery;
import org.sonar.api.scan.filesystem.ModuleFileSystem;

/**
 * SonarQube sensor for pitest mutation coverage analysis.
 *
 * <a href="mailto:aquiporras@gmail.com">Jaime Porras L&oacute;pez</a> <a href="mailto:alexvictoor@gmail.com">Alexandre Victoor</a>
 */
public class PitestSensor implements Sensor {

  private static final Logger LOG = LoggerFactory.getLogger(PitestSensor.class);

  private final JavaFileMutants noResourceMetrics = new JavaFileMutants();

  private final Settings settings;
  private final ResultParser parser;
  private final ReportFinder reportFinder;
  private final String executionMode;
  private final RulesProfile rulesProfile;
  private final ModuleFileSystem fileSystem;
  private final ResourcePerspectives perspectives;

  /**
   * Constructor
   * 
   * @param settings
   * @param parser
   * @param rulesProfile
   * @param reportFinder
   * @param fileSystem
   * @param perspectives
   */
  public PitestSensor(Settings settings, ResultParser parser, RulesProfile rulesProfile, ReportFinder reportFinder,
      ModuleFileSystem fileSystem, ResourcePerspectives perspectives) {
    this.settings = settings;
    this.parser = parser;
    this.reportFinder = reportFinder;
    this.fileSystem = fileSystem;
    this.perspectives = perspectives;
    this.executionMode = settings.getString(MODE_KEY);
    this.rulesProfile = rulesProfile;
  }

  /**
   * @param project
   * @return true if Sensor should execute, false otherwise
   */
  public final boolean shouldExecuteOnProject(Project project) {
    return project.getAnalysisType().isDynamic(true) && !fileSystem.files(FileQuery.onSource().onLanguage("java")).isEmpty()
      && !MODE_SKIP.equals(executionMode);
  }

  /**
   * Executes the Sensor
   * 
   * @param project
   * @param context
   */
  public final void analyse(Project project, SensorContext context) {
    List<ActiveRule> activeRules = rulesProfile.getActiveRulesByRepository(REPOSITORY_KEY);

    // Ignore violations from report, if rule not activated in SonarQube
    if (activeRules.isEmpty()) {
      LOG.warn("/!\\ PIT rule needs to be activated in the \"{}\" profile.", rulesProfile.getName());
      LOG.warn("Checkout plugin documentation for more detailed explanations: http://docs.codehaus.org/display/SONAR/Pitest");
    }

    File projectDirectory = fileSystem.baseDir();
    String reportDirectoryPath = settings.getString(REPORT_DIRECTORY_KEY);

    File reportDirectory = new File(projectDirectory, reportDirectoryPath);
    File xmlReport = reportFinder.findReport(reportDirectory);

    if (xmlReport == null) {
      LOG.warn("No XML PIT report found in directory {} !", reportDirectory);
      LOG.warn("Checkout plugin documentation for more detailed explanations: http://docs.codehaus.org/display/SONAR/Pitest");
    } else {
      Collection<Mutant> mutants = parser.parse(xmlReport);
      saveMutantsInfo(mutants, context, activeRules);
    }
  }

  private void saveMutantsInfo(Collection<Mutant> mutants, SensorContext context, List<ActiveRule> activeRules) {
    Map<Resource<?>, JavaFileMutants> metrics = collectMetrics(mutants, context, activeRules);

    for (Entry<Resource<?>, JavaFileMutants> entry : metrics.entrySet()) {
      saveMetricsInfo(context, entry.getKey(), entry.getValue());
    }
  }

  private void saveMetricsInfo(SensorContext context, Resource<?> resource, JavaFileMutants metricsInfo) {
    double detected = metricsInfo.getMutationsDetected();
    double total = metricsInfo.getMutationsTotal();

    context.saveMeasure(resource, PitestMetrics.MUTATIONS_TOTAL, total);
    context.saveMeasure(resource, PitestMetrics.MUTATIONS_NO_COVERAGE, metricsInfo.getMutationsNoCoverage());
    context.saveMeasure(resource, PitestMetrics.MUTATIONS_KILLED, metricsInfo.getMutationsKilled());
    context.saveMeasure(resource, PitestMetrics.MUTATIONS_SURVIVED, metricsInfo.getMutationsSurvived());
    context.saveMeasure(resource, PitestMetrics.MUTATIONS_MEMORY_ERROR, metricsInfo.getMutationsMemoryError());
    context.saveMeasure(resource, PitestMetrics.MUTATIONS_TIMED_OUT, metricsInfo.getMutationsTimedOut());
    context.saveMeasure(resource, PitestMetrics.MUTATIONS_UNKNOWN, metricsInfo.getMutationsUnknown());
    context.saveMeasure(resource, PitestMetrics.MUTATIONS_DETECTED, detected);

    saveData(context, resource, metricsInfo.getMutants());
  }

  private void saveData(SensorContext context, Resource<?> resource, List<Mutant> mutants) {
    if ((mutants != null) && ( !mutants.isEmpty())) {
      String json = Mutant.toJSON(mutants);
      Measure measure = new Measure(PitestMetrics.MUTATIONS_DATA, json);
      context.saveMeasure(resource, measure);
    }
  }

  private Map<Resource<?>, JavaFileMutants> collectMetrics(Collection<Mutant> mutants, SensorContext context, List<ActiveRule> activeRules) {
    Map<Resource<?>, JavaFileMutants> metricsByResource = new HashMap<Resource<?>, JavaFileMutants>();
    Rule rule = getSurvivedRule(activeRules); // Currently, only survived rule is applied
    Resource resource;

    for (Mutant mutant : mutants) {
      JavaFile file = new JavaFile(mutant.getSonarJavaFileKey());
      resource = context.getResource(file);

      if (resource == null) {
        LOG.warn("Mutation in an unknown resource: {}", mutant.getSonarJavaFileKey());
        LOG.debug("Mutant: {}", mutant);
        noResourceMetrics.addMutant(mutant);
      } else {
        processMutant(mutant, getMetricsInfo(metricsByResource, resource), resource, rule);
      }
    }

    return metricsByResource;
  }

  private Rule getSurvivedRule(List<ActiveRule> activeRules) {
    Rule rule = null;

    if (activeRules != null && !activeRules.isEmpty()) {
      rule = activeRules.get(0).getRule();
    }

    return rule;
  }

  private void processMutant(Mutant mutant, JavaFileMutants resourceMetricsInfo, Resource resource, Rule rule) {
    resourceMetricsInfo.addMutant(mutant);

    if (rule != null && MutantStatus.SURVIVED.equals(mutant.getMutantStatus())) {
      // Only survived mutations are saved as violations
      Issuable issuable = perspectives.as(Issuable.class, resource);

      if (issuable != null) {
        // can be used
        Issue issue = issuable.newIssueBuilder().ruleKey(RuleKey.of(PitestConstants.REPOSITORY_KEY, PitestConstants.RULE_KEY))
            .line(mutant.getLineNumber()).message(mutant.getViolationDescription()).build();
        issuable.addIssue(issue);
      }
    }
  }

  private static JavaFileMutants getMetricsInfo(Map<Resource<?>, JavaFileMutants> metrics, Resource<?> resource) {
    JavaFileMutants metricsInfo = null;

    if (resource != null) {
      metricsInfo = metrics.get(resource);

      if (metricsInfo == null) {
        metricsInfo = new JavaFileMutants();
        metrics.put(resource, metricsInfo);
      }
    }

    return metricsInfo;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final String toString() {
    return getClass().getSimpleName();
  }
}