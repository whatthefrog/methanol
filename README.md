# Methanol

A lightweight library that complements `java.net.http` for a better HTTP experience.

[![CI status](https://github.com/mizosoft/methanol/workflows/CI/badge.svg)](https://github.com/mizosoft/methanol/actions)
[![Coverage Status](https://coveralls.io/repos/github/mizosoft/methanol/badge.svg?branch=master)](https://coveralls.io/github/mizosoft/methanol?branch=master)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.mizosoft.methanol/methanol.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22com.github.mizosoft.methanol%22%20AND%20a:%22methanol%22)

## Overview

***Methanol*** provides useful lightweight HTTP extensions built on top of `java.net.http`.
Applications using Java's non-blocking HTTP client shall find it more robust and easier to use with
Methanol.

### Features

* Automatic response decompression.
* Special `BodyPublisher` implementations for form submission.
* An extensible object conversion mechanism, with optional support for JSON, XML and Protocol Buffers.
* A custom `HttpClient` that allows request decoration and async `Publisher<HttpResponse<T>>` dispatches.
* Additional `BodyPublisher`, `BodySubscriber` and `BodyHandler` implementations.

## Installation

### Gradle

```gradle
dependencies {
  implementation 'com.github.mizosoft.methanol:methanol:1.2.0'
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>com.github.mizosoft.methanol</groupId>
    <artifactId>methanol</artifactId>
    <version>1.2.0</version>
  </dependency>
</dependencies>
```

## Examples

### Response decompression

The HTTP client has no native decompression support. Methanol ameliorates this in a flexible and
reactive-friendly way so that you don't have to use blocking streams like `GZIPInputStream`.

```java
final HttpClient client = HttpClient.newHttpClient();

<T> T get(String url, BodyHandler<T> handler) throws IOException, InterruptedException {
  MutableRequest request = MutableRequest.GET(url)
      .header("Accept-Encoding", "gzip");
  HttpResponse<T> response = client.send(request, MoreBodyHandlers.decoding(handler));
  int statusCode = response.statusCode();
  if (statusCode < 200 || statusCode > 299) {
    throw new IOException("failed response: " + statusCode);
  }

  return response.body();
}
```

### Object conversion

Methanol provides a  flexible mechanism for dynamically converting objects to or from request
or response bodies respectively. This example interacts with GitHub's JSON API. It is assumed
you have [methanol-gson](methanol-gson) or [methanol-jackson](methanol-jackson) installed.

```java
final Methanol client = Methanol.newBuilder()
    .baseUri("https://api.github.com")
    .defaultHeader("Accept", "application/vnd.github.v3+json")
    .build();

GitHubUser getUser(String name) throws IOException, InterruptedException {
  MutableRequest request = MutableRequest.GET("/users/" + name);
  HttpResponse<GitHubUser> response =
      client.send(request, MoreBodyHandlers.ofObject(GitHubUser.class));

  return response.body();
}

// For complex types, use a TypeRef
List<GitHubUser> getUserFollowers(String userName) throws IOException, InterruptedException {
  MutableRequest request = MutableRequest.GET("/users/" + userName + "/followers");
  HttpResponse<List<GitHubUser>> response =
      client.send(request, MoreBodyHandlers.ofObject(new TypeRef<List<GitHubUser>>() {}));

  return response.body();
}

String renderMarkdown(RenderRequest renderRequest) throws IOException, InterruptedException {
  BodyPublisher requestBody = MoreBodyPublishers.ofObject(renderRequest, MediaType.APPLICATION_JSON);
  // No need to set Content-Type header!
  MutableRequest request = MutableRequest.POST("/markdown", requestBody)
      .header("Accept", "text/html");
  HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

  return response.body();
}

static class GitHubUser {
  public String login;
  public long id;
  public String bio;
  // other fields omitted
}

static class RenderRequest {
  public String text, mode, context;
}
```

### Form bodies

This example downloads an article from Wikipedia using a provided search query.

```java
final Methanol client = Methanol.newBuilder()
    .baseUri("https://en.wikipedia.org")
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build();

Path downloadArticle(String title) throws IOException, InterruptedException {
  FormBodyPublisher searchQuery = FormBodyPublisher.newBuilder()
      .query("search", title)
      .build();
  MutableRequest request = MutableRequest.POST("/wiki/Main_Page", searchQuery);
  HttpResponse<Path> response =
      client.send(request, BodyHandlers.ofFile(Path.of(title + ".html")));

  return response.body();
}
```

### Multipart bodies

This example uploads an image to the [imgur](https://imgur.com) image sharing service using a
multipart body.

```java
final Methanol client = Methanol.newBuilder()
    .baseUri("https://api.imgur.com/3/")
    .defaultHeader("Authorization", "Client-ID " + System.getenv("IMGUR_CLIENT_ID")) // substitute with your client ID
    .build();

URI uploadToImgur(String title, Path image) throws IOException, InterruptedException {
  MultipartBodyPublisher imageUpload = MultipartBodyPublisher.newBuilder()
      .textPart("title", title)
      .filePart("image", image)
      .build();
  MutableRequest request = MutableRequest.POST("upload", imageUpload);
  HttpResponse<Reader> response = client.send(request, MoreBodyHandlers.ofReader());

  try (Reader reader = response.body()) {
    String link = com.google.gson.JsonParser.parseReader(reader)
        .getAsJsonObject()
        .getAsJsonObject("data")
        .get("link")
        .getAsString();

    return URI.create(link);
  }
}
```

### Reactive request dispatches

For a truly reactive experience, one might want to dispatch async requests as
`Publisher<HttpResponse<T>>` sources. `Methanol` client complements `sendAsync` with `exchange` for
such a task. This example assumes you have [methanol-jackson-flux](methanol-jackson-flux) installed.

```java
final Methanol client = Methanol.newBuilder()
    .baseUri("https://api.github.com")
    .defaultHeader("Accept", "application/vnd.github.v3+json")
    .build();

Flux<GitHubUser> getContributors(String repo) {
  MutableRequest request = MutableRequest.GET("/repos/" + repo + "/contributors");
  Publisher<HttpResponse<Flux<GitHubUser>>> publisher =
      client.exchange(request, MoreBodyHandlers.ofObject(new TypeRef<Flux<GitHubUser>>() {}));
  
  return JdkFlowAdapter.flowPublisherToFlux(publisher).flatMap(HttpResponse::body);
}
```

#### Push promises

This also works well with push-promise enabled servers. Here, the publisher streams a non-ordered
sequence including the main response along with other resources pushed by the server.

```java
Methanol client = Methanol.create(); // default Version is HTTP_2
MutableRequest request = MutableRequest.GET("https://http2.golang.org/serverpush");
Publisher<HttpResponse<Path>> publisher =
    client.exchange(
        request,
        BodyHandlers.ofFile(Path.of("index.html")),
        promise -> BodyHandlers.ofFile(Path.of(promise.uri().getPath()).getFileName()));
JdkFlowAdapter.flowPublisherToFlux(publisher)
    .filter(res -> res.statusCode() == 200)
    .map(HttpResponse::body)
    .subscribe(System.out::println);
```

## Documentation

* [User Guide](UserGuide.md)
* [Javadoc](https://mizosoft.github.io/methanol/1.x/doc/)

## License

[MIT](https://choosealicense.com/licenses/mit/)
