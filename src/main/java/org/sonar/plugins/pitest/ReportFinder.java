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

import org.apache.commons.io.FileUtils;
import org.sonar.api.BatchExtension;

import java.io.File;
import java.util.Collection;

public class ReportFinder implements BatchExtension {

  public File findReport(File reportDirectory) {
    if (reportDirectory== null || !reportDirectory.exists() || !reportDirectory.isDirectory()) {
      return null;
    }
    Collection<File> reports = FileUtils.listFiles(reportDirectory, new String[]{"xml"}, true);
    File latestReport = null;
    for (File report : reports) {
      if (latestReport == null || FileUtils.isFileNewer(report, latestReport)) {
        latestReport = report;
      }
    }
    return latestReport;
  }

}
