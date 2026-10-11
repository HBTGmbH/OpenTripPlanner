package org.opentripplanner.netex.loader;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentripplanner.datastore.api.DataSource;
import org.opentripplanner.datastore.api.FileType;
import org.opentripplanner.datastore.file.DirectoryDataSource;

class NetexDataSourceHierarchyTest {

  private static final Pattern IGNORE = Pattern.compile("$^");
  private static final Pattern SHARED = Pattern.compile("shared\\.xml");
  private static final Pattern SHARED_GROUP = Pattern.compile("(\\w{3})-.*-shared\\.xml");
  private static final Pattern GROUP = Pattern.compile("(\\w{3})-.*\\.xml");

  @TempDir
  File dir;

  @Test
  void ignoreUnmatchedFilesByDefault() throws IOException {
    var hierarchy = hierarchy(false, "shared.xml", "RUT-line-1.xml", "single-file.xml");

    assertThat(names(hierarchy.sharedEntries())).containsExactly("shared.xml");
    assertThat(groupNames(hierarchy)).containsExactly("RUT");
    assertThat(hierarchy.unmatchedEntryNames()).containsExactly("single-file.xml");
  }

  @Test
  void loadUnmatchedFilesAsGroups() throws IOException {
    var hierarchy = hierarchy(true, "RUT-line-1.xml", "single-file.xml");

    assertThat(groupNames(hierarchy)).containsExactly("RUT", "single-file.xml");
    assertThat(hierarchy.unmatchedEntryNames()).containsExactly("single-file.xml");
  }

  private NetexDataSourceHierarchy hierarchy(boolean loadUnmatchedFilesAsGroups, String... files)
    throws IOException {
    for (String file : files) {
      Files.writeString(new File(dir, file).toPath(), "<xml/>");
    }
    return new NetexDataSourceHierarchy(new DirectoryDataSource(dir, FileType.NETEX)).prepare(
      IGNORE,
      SHARED,
      SHARED_GROUP,
      GROUP,
      loadUnmatchedFilesAsGroups
    );
  }

  private static List<String> names(Iterable<DataSource> entries) {
    var result = new ArrayList<String>();
    entries.forEach(it -> result.add(it.name()));
    return result;
  }

  private static List<String> groupNames(NetexDataSourceHierarchy hierarchy) {
    var result = new ArrayList<String>();
    hierarchy.groups().forEach(it -> result.add(it.name()));
    return result;
  }
}
