package eu.wohlben.qits.npm;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.MachineTokens;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** npm's ceremonial publish token must not become an OIDC credential on the tokenless raw route. */
@QuarkusTest
@TestProfile(MachineTokens.Enforced.class)
class NpmOpenPublishTest {

  @TestHTTPResource("/") URL root;

  @BeforeEach
  void ensureRepository() {
    given()
        .contentType(ContentType.JSON)
        .header("Authorization", "Bearer " + MachineTokens.forThisService())
        .body(Map.of("type", "npm-packages"))
        .when()
        .put("/artifacts/api/repositories/npm")
        .then()
        .statusCode(200);
  }

  @Test
  void dummyNpmTokenDoesNotTriggerOidcOnTheRawRegistryRoute() {
    TinyPackage subject = TinyPackage.of("@qits/proactive-auth-regression", "1.0.0");
    try (NpmClient npm =
        new NpmClient(URI.create(root.toString())).header("Authorization", "Bearer qits-ci")) {
      assertEquals(
          201,
          npm.publish("npm", "@qits%2fproactive-auth-regression", subject.publishDocument("latest"))
              .statusCode());
    }
  }
}
