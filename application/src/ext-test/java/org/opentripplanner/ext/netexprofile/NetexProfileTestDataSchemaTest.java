package org.opentripplanner.ext.netexprofile;

import static com.google.common.truth.Truth.assertThat;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The hand-built EPIP and VDV 462 test data must be valid NeTEx, so the tests do not rely on input the
 * importer only accepts by accident. The files are validated against the NeTEx schema shipped with
 * netex-java-model. The variant without key constraints is used, because the files reference objects
 * defined in another file or not at all (time demand types).
 */
class NetexProfileTestDataSchemaTest {

  private static final String SCHEMA = "xsd/1.16.1/NeTEx_publication-NoConstraint.xsd";
  private static final List<File> DATA_DIRS = List.of(
    new File("src/ext-test/resources/org/opentripplanner/ext/netexprofile/epip/routing/netex"),
    new File("src/ext-test/resources/org/opentripplanner/ext/netexprofile/vdv462/timing/netex")
  );

  private static Schema schema;

  @BeforeAll
  static void loadSchema() throws Exception {
    var url = NetexProfileTestDataSchemaTest.class.getClassLoader().getResource(SCHEMA);
    assertThat(url).isNotNull();
    schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(url);
  }

  static Stream<File> files() {
    return DATA_DIRS.stream().flatMap(dir -> {
      var files = dir.listFiles((d, name) -> name.endsWith(".xml"));
      assertThat(files).isNotEmpty();
      return Arrays.stream(files).sorted();
    });
  }

  @ParameterizedTest
  @MethodSource("files")
  void fileIsValidNetex(File file) throws Exception {
    schema.newValidator().validate(new StreamSource(file));
  }
}
