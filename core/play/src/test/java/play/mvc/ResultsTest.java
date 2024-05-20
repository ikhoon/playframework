/*
 * Copyright (C) from 2022 The Play Framework Contributors <https://github.com/playframework>, 2011-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package play.mvc;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.stream.Materializer;
import org.apache.pekko.stream.javadsl.Sink;
import org.junit.*;
import play.libs.typedmap.TypedEntry;
import play.libs.typedmap.TypedKey;
import play.libs.typedmap.TypedMap;
import play.mvc.Http.HeaderNames;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.jdk.javaapi.FutureConverters;

public class ResultsTest {

  private static Path file;
  private static final boolean INLINE_FILE = true;
  private static final boolean ATTACHMENT_FILE = false;

  @BeforeClass
  public static void createFile() throws Exception {
    file = Paths.get("test.tmp");
    Files.createFile(file);
    Files.write(file, "Some content for the file".getBytes(), StandardOpenOption.APPEND);
  }

  @AfterClass
  public static void deleteFile() throws IOException {
    Files.deleteIfExists(file);
  }

  @Test
  public void shouldCopyFlashWhenCallingResultAs() {
    Map<String, String> flash = new HashMap<>();
    flash.put("flash.message", "flash message value");
    Result result = Results.redirect("/somewhere").withFlash(flash);

    Result as = result.as(Http.MimeTypes.HTML);
    assertNotNull(as.flash());
    assertTrue(as.flash().get("flash.message").isPresent());
    assertEquals("flash message value", as.flash().get("flash.message").get());
  }

  @Test
  public void shouldCopySessionWhenCallingResultAs() {
    Map<String, String> session = new HashMap<>();
    session.put("session.message", "session message value");
    Result result = Results.ok("Result test body").withSession(session);

    Result as = result.as(Http.MimeTypes.HTML);
    assertNotNull(as.session());
    assertTrue(as.session().get("session.message").isPresent());
    assertEquals("session message value", as.session().get("session.message").get());
  }

  @Test
  public void shouldCopyHeadersWhenCallingResultAs() {
    Result result = Results.ok("Result test body").withHeader("X-Header", "header value");
    Result as = result.as(Http.MimeTypes.HTML);
    assertEquals("header value", as.header("X-Header").get());
  }

  @Test
  public void shouldCopyCookiesWhenCallingResultAs() {
    Result result =
        Results.ok("Result test body")
            .withCookies(Http.Cookie.builder("cookie-name", "cookie value").build())
            .as(Http.MimeTypes.HTML);

    assertEquals("cookie value", result.cookie("cookie-name").get().value());
  }

  // -- Path tests

  @Test(expected = NullPointerException.class)
  public void shouldThrowNullPointerExceptionIfPathIsNull() {
    Results.ok().sendPath(null);
  }

  @Test
  public void sendPathWithOKStatus() {
    Result result = Results.ok().sendPath(file);
    assertEquals(Http.Status.OK, result.status());
    assertEquals(
        "inline; filename=\"test.tmp\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendPathWithUnauthorizedStatus() {
    Result result = Results.unauthorized().sendPath(file);
    assertEquals(Http.Status.UNAUTHORIZED, result.status());
    assertEquals(
        "inline; filename=\"test.tmp\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendPathAsAttachmentWithUnauthorizedStatus() {
    Result result = Results.unauthorized().sendPath(file, ATTACHMENT_FILE);
    assertEquals(Http.Status.UNAUTHORIZED, result.status());
    assertEquals(
        "attachment; filename=\"test.tmp\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendPathAsAttachmentWithOkStatus() {
    Result result = Results.ok().sendPath(file, ATTACHMENT_FILE);
    assertEquals(Http.Status.OK, result.status());
    assertEquals(
        "attachment; filename=\"test.tmp\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendPathWithFileName() {
    Result result = Results.unauthorized().sendPath(file, Optional.of("foo.bar"));
    assertEquals(Http.Status.UNAUTHORIZED, result.status());
    assertEquals(
        "inline; filename=\"foo.bar\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendPathInlineWithFileName() {
    Result result = Results.unauthorized().sendPath(file, INLINE_FILE, Optional.of("foo.bar"));
    assertEquals(Http.Status.UNAUTHORIZED, result.status());
    assertEquals(
        "inline; filename=\"foo.bar\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendPathInlineWithoutFileName() {
    Result result = Results.unauthorized().sendPath(file, Optional.empty());
    assertEquals(Http.Status.UNAUTHORIZED, result.status());
    assertEquals(Optional.empty(), result.header(HeaderNames.CONTENT_DISPOSITION));
  }

  @Test
  public void sendPathAsAttachmentWithoutFileName() {
    Result result = Results.unauthorized().sendPath(file, ATTACHMENT_FILE, Optional.empty());
    assertEquals(Http.Status.UNAUTHORIZED, result.status());
    assertEquals("attachment", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendPathWithFileNameHasSpecialChars() {
    Result result = Results.ok().sendPath(file, INLINE_FILE, Optional.of("测 试.tmp"));
    assertEquals(Http.Status.OK, result.status());
    assertEquals(
        "inline; filename=\"? ?.tmp\"; filename*=utf-8''%e6%b5%8b%20%e8%af%95.tmp",
        result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  // -- File tests

  @Test(expected = NullPointerException.class)
  public void shouldThrowNullPointerExceptionIfFileIsNull() {
    Results.ok().sendFile(null);
  }

  @Test
  public void sendFileWithOKStatus() {
    Result result = Results.ok().sendFile(file.toFile());
    assertEquals(Http.Status.OK, result.status());
    assertEquals(
        "inline; filename=\"test.tmp\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendFileWithUnauthorizedStatus() {
    Result result = Results.unauthorized().sendFile(file.toFile());
    assertEquals(Http.Status.UNAUTHORIZED, result.status());
    assertEquals(
        "inline; filename=\"test.tmp\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendFileAsAttachmentWithUnauthorizedStatus() {
    Result result = Results.unauthorized().sendFile(file.toFile(), ATTACHMENT_FILE);
    assertEquals(Http.Status.UNAUTHORIZED, result.status());
    assertEquals(
        "attachment; filename=\"test.tmp\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendFileAsAttachmentWithOkStatus() {
    Result result = Results.ok().sendFile(file.toFile(), ATTACHMENT_FILE);
    assertEquals(Http.Status.OK, result.status());
    assertEquals(
        "attachment; filename=\"test.tmp\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendFileWithFileName() {
    Result result = Results.unauthorized().sendFile(file.toFile(), Optional.of("foo.bar"));
    assertEquals(Http.Status.UNAUTHORIZED, result.status());
    assertEquals(
        "inline; filename=\"foo.bar\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendFileInlineWithFileName() {
    Result result = Results.ok().sendFile(file.toFile(), INLINE_FILE, Optional.of("foo.bar"));
    assertEquals(Http.Status.OK, result.status());
    assertEquals(
        "inline; filename=\"foo.bar\"", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendFileInlineWithoutFileName() {
    Result result = Results.ok().sendFile(file.toFile(), Optional.empty());
    assertEquals(Http.Status.OK, result.status());
    assertEquals(Optional.empty(), result.header(HeaderNames.CONTENT_DISPOSITION));
  }

  @Test
  public void sendFileAsAttachmentWithoutFileName() {
    Result result = Results.ok().sendFile(file.toFile(), ATTACHMENT_FILE, Optional.empty());
    assertEquals(Http.Status.OK, result.status());
    assertEquals("attachment", result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendFileWithFileNameHasSpecialChars() {
    Result result = Results.ok().sendFile(file.toFile(), INLINE_FILE, Optional.of("测 试.tmp"));
    assertEquals(Http.Status.OK, result.status());
    assertEquals(
        "inline; filename=\"? ?.tmp\"; filename*=utf-8''%e6%b5%8b%20%e8%af%95.tmp",
        result.header(HeaderNames.CONTENT_DISPOSITION).get());
  }

  @Test
  public void sendFileHonoringOnClose() throws TimeoutException, InterruptedException {
    ActorSystem actorSystem = ActorSystem.create("TestSystem");
    Materializer mat = Materializer.matFromSystem(actorSystem);
    try {
      AtomicBoolean fileSent = new AtomicBoolean(false);
      Result result = Results.ok().sendFile(file.toFile(), () -> fileSent.set(true), null);

      // Actually we need to wait until the Stream completes
      Await.ready(
          FutureConverters.asScala(result.body().dataStream().runWith(Sink.ignore(), mat)),
          Duration.create("60s"));
      // and then we need to wait until the onClose completes
      Thread.sleep(500);

      assertTrue(fileSent.get());
      assertEquals(Http.Status.OK, result.status());
    } finally {
      Await.ready(actorSystem.terminate(), Duration.create("60s"));
    }
  }

  @Test
  public void sendPathHonoringOnClose() throws TimeoutException, InterruptedException {
    ActorSystem actorSystem = ActorSystem.create("TestSystem");
    Materializer mat = Materializer.matFromSystem(actorSystem);
    try {
      AtomicBoolean fileSent = new AtomicBoolean(false);
      Result result = Results.ok().sendPath(file, () -> fileSent.set(true), null);

      // Actually we need to wait until the Stream completes
      Await.ready(
          FutureConverters.asScala(result.body().dataStream().runWith(Sink.ignore(), mat)),
          Duration.create("60s"));
      // and then we need to wait until the onClose completes
      Thread.sleep(500);

      assertTrue(fileSent.get());
      assertEquals(Http.Status.OK, result.status());
    } finally {
      Await.ready(actorSystem.terminate(), Duration.create("60s"));
    }
  }

  @Test
  public void sendResourceHonoringOnClose() throws TimeoutException, InterruptedException {
    ActorSystem actorSystem = ActorSystem.create("TestSystem");
    Materializer mat = Materializer.matFromSystem(actorSystem);
    try {
      AtomicBoolean fileSent = new AtomicBoolean(false);
      Result result =
          Results.ok().sendResource("multipart-form-data-file.txt", () -> fileSent.set(true), null);

      // Actually we need to wait until the Stream completes
      Await.ready(
          FutureConverters.asScala(result.body().dataStream().runWith(Sink.ignore(), mat)),
          Duration.create("60s"));
      // and then we need to wait until the onClose completes
      Thread.sleep(500);

      assertTrue(fileSent.get());
      assertEquals(Http.Status.OK, result.status());
    } finally {
      Await.ready(actorSystem.terminate(), Duration.create("60s"));
    }
  }

  @Test
  public void sendInputStreamHonoringOnClose() throws TimeoutException, InterruptedException {
    ActorSystem actorSystem = ActorSystem.create("TestSystem");
    Materializer mat = Materializer.matFromSystem(actorSystem);
    try {
      AtomicBoolean fileSent = new AtomicBoolean(false);
      Result result =
          Results.ok()
              .sendInputStream(
                  new ByteArrayInputStream("test data".getBytes()),
                  9,
                  () -> fileSent.set(true),
                  null);

      // Actually we need to wait until the Stream completes
      Await.ready(
          FutureConverters.asScala(result.body().dataStream().runWith(Sink.ignore(), mat)),
          Duration.create("60s"));
      // and then we need to wait until the onClose completes
      Thread.sleep(500);

      assertTrue(fileSent.get());
      assertEquals(Http.Status.OK, result.status());
    } finally {
      Await.ready(actorSystem.terminate(), Duration.create("60s"));
    }
  }

  @Test
  public void sendInputStreamChunkedHonoringOnClose()
      throws TimeoutException, InterruptedException {
    ActorSystem actorSystem = ActorSystem.create("TestSystem");
    Materializer mat = Materializer.matFromSystem(actorSystem);
    try {
      AtomicBoolean fileSent = new AtomicBoolean(false);
      Result result =
          Results.ok()
              .sendInputStream(
                  new ByteArrayInputStream("test data".getBytes()), () -> fileSent.set(true), null);

      // Actually we need to wait until the Stream completes
      Await.ready(
          FutureConverters.asScala(result.body().dataStream().runWith(Sink.ignore(), mat)),
          Duration.create("60s"));
      // and then we need to wait until the onClose completes
      Thread.sleep(500);

      assertTrue(fileSent.get());
      assertEquals(Http.Status.OK, result.status());
    } finally {
      Await.ready(actorSystem.terminate(), Duration.create("60s"));
    }
  }

  @Test
  public void getOptionalCookie() {
    Result result =
        Results.ok()
            .withCookies(new Http.Cookie("foo", "1", 1000, "/", "example.com", false, true, null));
    assertTrue(result.cookie("foo").isPresent());
    assertEquals("foo", result.cookie("foo").get().name());
    assertFalse(result.cookie("bar").isPresent());
  }

  @Test
  public void redirectShouldReturnTheSameUrlIfTheQueryStringParamsMapIsEmpty() {
    Map<String, List<String>> queryStringParameters = new HashMap<>();
    String url = "/somewhere";
    Result result = Results.redirect(url, queryStringParameters);
    assertTrue(result.redirectLocation().isPresent());
    assertEquals(url, result.redirectLocation().get());
  }

  @Test
  public void redirectAppendGivenQueryStringParamsToTheUrlIfUrlContainsQuestionMark() {
    Map<String, List<String>> queryStringParameters = new HashMap<>();
    queryStringParameters.put("param1", Arrays.asList("value1"));
    String url = "/somewhere?param2=value2";

    String expectedRedirectUrl = "/somewhere?param2=value2&param1=value1";

    Result result = Results.redirect(url, queryStringParameters);
    assertTrue(result.redirectLocation().isPresent());
    assertEquals(expectedRedirectUrl, result.redirectLocation().get());
  }

  @Test
  public void redirectShouldAddQueryStringParamsToTheUrl() {
    Map<String, List<String>> queryStringParameters = new HashMap<>();
    queryStringParameters.put("param1", Arrays.asList("value1"));
    queryStringParameters.put("param2", Arrays.asList("value2"));
    String url = "/somewhere";

    String expectedParam1 = "param1=value1";
    String expectedParam2 = "param2=value2";

    Result result = Results.redirect(url, queryStringParameters);
    assertTrue(result.redirectLocation().isPresent());
    assertTrue(result.redirectLocation().get().contains(expectedParam1));
    assertTrue(result.redirectLocation().get().contains(expectedParam2));
  }

  @Test
  public void canAddAttributes() {
    TypedKey<String> x = TypedKey.create("x");
    TypedMap attrs = TypedMap.create(new TypedEntry<>(x, "y"));
    Result result = Results.ok().withAttrs(attrs);
    assertTrue(result.attrs().containsKey(x));
    assertEquals("y", result.attrs().get(x));
  }

  @Test
  public void keepAttributesWhenModifyingHeader() {
    TypedKey<String> x = TypedKey.create("x");
    TypedMap attrs = TypedMap.create(new TypedEntry<>(x, "y"));

    Result a = Results.ok().withAttrs(attrs).withHeader("foo", "bar");
    assertTrue(a.attrs().containsKey(x));
    assertEquals("y", a.attrs().get(x));

    Result b = Results.ok().withAttrs(attrs).withHeaders("foo", "bar");
    assertTrue(b.attrs().containsKey(x));
    assertEquals("y", b.attrs().get(x));

    Result c = Results.ok().withAttrs(attrs).withoutHeader("foo");
    assertTrue(c.attrs().containsKey(x));
    assertEquals("y", c.attrs().get(x));
  }

  @Test
  public void keepAttributesWhenModifyingFlash() {
    TypedKey<String> x = TypedKey.create("x");
    TypedMap attrs = TypedMap.create(new TypedEntry<>(x, "y"));
    Result result =
        Results.redirect("/").withAttrs(attrs).withFlash(new Http.Flash(Map.of("foo", "bar")));
    assertTrue(result.attrs().containsKey(x));
    assertEquals("y", result.attrs().get(x));
  }

  @Test
  public void keepAttributesWhenModifyingSession() {
    TypedKey<String> x = TypedKey.create("x");
    TypedMap attrs = TypedMap.create(new TypedEntry<>(x, "y"));
    Result result =
        Results.ok().withAttrs(attrs).withSession(new Http.Session(Map.of("foo", "bar")));
    assertTrue(result.attrs().containsKey(x));
    assertEquals("y", result.attrs().get(x));
  }

  @Test
  public void keepAttributesWhenModifyingContentType() {
    TypedKey<String> x = TypedKey.create("x");
    TypedMap attrs = TypedMap.create(new TypedEntry<>(x, "y"));
    Result result = Results.ok().withAttrs(attrs).as(Http.MimeTypes.TEXT);
    assertTrue(result.attrs().containsKey(x));
    assertEquals("y", result.attrs().get(x));
  }
}
