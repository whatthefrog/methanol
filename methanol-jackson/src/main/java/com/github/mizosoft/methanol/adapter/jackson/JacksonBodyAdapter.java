/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol.adapter.jackson;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.TypeReference;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

abstract class JacksonBodyAdapter extends AbstractBodyAdapter {

  public static final MediaType APPLICATION_JSON = MediaType.of("application", "json");
  public static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;

  final ObjectMapper mapper;

  JacksonBodyAdapter(ObjectMapper mapper) {
    super(APPLICATION_JSON);
    this.mapper = requireNonNull(mapper);
  }

  static final class Encoder extends JacksonBodyAdapter implements BodyAdapter.Encoder {

    Encoder(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean supportsType(TypeReference<?> type) {
      requireNonNull(type);
      return mapper.canSerialize(type.rawType());
    }

    @Override
    public BodyPublisher toBody(Object object, @Nullable MediaType mediaType) {
      requireNonNull(object);
      requireSupport(object.getClass());
      requireCompatibleOrNull(mediaType);
      ObjectWriter objWriter = mapper.writerFor(object.getClass());
      ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
      try (Writer writer =
          new OutputStreamWriter(outBuffer, charsetOrDefault(mediaType, DEFAULT_CHARSET))) {
        objWriter.writeValue(writer, object);
      } catch (JsonProcessingException e) {
        throw new UncheckedIOException(e);
      } catch (IOException ioe) {
        throw new AssertionError(ioe); // writing to a memory buffer
      }
      return attachMediaType(BodyPublishers.ofByteArray(outBuffer.toByteArray()), mediaType);
    }
  }

  static final class Decoder extends JacksonBodyAdapter implements BodyAdapter.Decoder {

    Decoder(ObjectMapper mapper) {
      super(mapper);
    }

    @Override
    public boolean supportsType(TypeReference<?> type) {
      return mapper.canDeserialize(mapper.constructType(type.type()));
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeReference<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      Charset charset = charsetOrDefault(mediaType, DEFAULT_CHARSET);
      // The non-blocking parser only works with UTF-8 and ASCII
      // https://github.com/FasterXML/jackson-core/issues/596
      EncodingProcessor processor =
          charset.equals(StandardCharsets.US_ASCII) || charset.equals(StandardCharsets.UTF_8)
              ? (buffs, eof) -> buffs // NOOP
              : new ToUtf8Processor(charset.newDecoder());
      try {
        return new JacksonSubscriber<>(
            mapper, type, mapper.getFactory().createNonBlockingByteArrayParser(), processor);
      } catch (IOException | UnsupportedOperationException ignored) {
        // Fallback to de-serializing from byte array
        return BodySubscribers.mapping(
            BodySubscribers.ofByteArray(), bytes -> readValueUnchecked(type, bytes));
      }
    }

    @Override
    public <T> BodySubscriber<Supplier<T>> toDeferredObject(
        TypeReference<T> type, @Nullable MediaType mediaType) {
      requireNonNull(type);
      requireSupport(type);
      requireCompatibleOrNull(mediaType);
      return BodySubscribers.mapping(
          MoreBodySubscribers.ofReader(charsetOrDefault(mediaType, DEFAULT_CHARSET)),
          reader -> () -> readValueUnchecked(type, reader));
    }

    private <T> T readValueUnchecked(TypeReference<T> type, byte[] body) {
      try {
        JsonParser parser = mapper.getFactory().createParser(body);
        return mapper.readerFor(mapper.constructType(type.type())).readValue(parser);
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    }

    private <T> T readValueUnchecked(TypeReference<T> type, Reader reader) {
      try {
        return mapper.readerFor(mapper.constructType(type.type())).readValue(reader);
      } catch (IOException ioe) {
        throw new UncheckedIOException(ioe);
      }
    }

    private interface EncodingProcessor {

      List<ByteBuffer> process(List<ByteBuffer> input, boolean endOfInput)
          throws CharacterCodingException;
    }

    // This "function" decodes body using response charset then encodes
    // it again in UTF-8 to be usable with the non-blocking parser
    private static final class ToUtf8Processor implements EncodingProcessor {

      private static final int TEMP_BUFFER_SIZE = 4 * 1024;

      private final CharsetDecoder decoder;
      private final CharsetEncoder utf8Encoder;
      private final CharBuffer tempCharBuff;
      private @Nullable ByteBuffer leftover;

      ToUtf8Processor(CharsetDecoder decoder) {
        this.decoder = decoder;
        utf8Encoder = StandardCharsets.UTF_8.newEncoder();
        tempCharBuff = CharBuffer.allocate(TEMP_BUFFER_SIZE);
      }

      @Override
      public List<ByteBuffer> process(List<ByteBuffer> item, boolean endOfInput)
          throws CharacterCodingException {
        List<ByteBuffer> processed = new ArrayList<>(item.size());
        for (ByteBuffer buffer : item) {
          ByteBuffer processedBuffer = processBuffer(buffer, endOfInput);
          if (processedBuffer.hasRemaining()) {
            processed.add(processedBuffer);
          }
        }
        return processed;
      }

      private ByteBuffer processBuffer(ByteBuffer input, boolean endOfInput)
          throws CharacterCodingException {
        if (leftover != null) {
          // add leftover bytes from previous round
          input =
              ByteBuffer.allocate(leftover.remaining() + input.remaining())
                  .put(leftover)
                  .put(input)
                  .flip();
          leftover = null;
        }
        int cap =
            (int)
                (input.remaining()
                    * decoder.averageCharsPerByte()
                    * utf8Encoder.averageBytesPerChar());
        ByteBuffer out = ByteBuffer.allocate(cap);
        while (true) {
          CoderResult decoderResult = decoder.decode(input, tempCharBuff, endOfInput);
          if (decoderResult.isUnderflow() && endOfInput) {
            decoderResult = decoder.flush(tempCharBuff);
          }
          if (decoderResult.isError()) {
            decoderResult.throwException();
          }
          // it's not eoi for encoder unless decoder also finished (underflow result)
          boolean endOfInputForEncoder = decoderResult.isUnderflow() && endOfInput;
          CoderResult encoderResult =
              utf8Encoder.encode(tempCharBuff.flip(), out, endOfInputForEncoder);
          tempCharBuff.compact();
          if (encoderResult.isUnderflow() && endOfInputForEncoder) {
            encoderResult = utf8Encoder.flush(out);
          }
          if (encoderResult.isError()) {
            encoderResult.throwException();
          }
          if (encoderResult.isOverflow()) { // need bigger out buffer
            cap = cap + Math.max(1, cap >> 1);
            ByteBuffer newOut = ByteBuffer.allocate(cap);
            newOut.put(out.flip());
            out = newOut;
          } else if (encoderResult.isUnderflow() && decoderResult.isUnderflow()) { // round finished
            if (input.hasRemaining()) {
              leftover = input.slice(); // save for next round
            }
            return out.flip();
          }
        }
      }
    }

    private static final class JacksonSubscriber<T> implements BodySubscriber<T> {

      private static final List<ByteBuffer> EMPTY_BUFFER_LIST = List.of(ByteBuffer.allocate(0));

      private final ObjectMapper mapper;
      private final ObjectReader objReader;
      private final JsonParser parser;
      private final EncodingProcessor processor;
      private final ByteArrayFeeder feeder;
      private final TokenBuffer jsonBuffer;
      private final CompletableFuture<T> valueFuture;
      private final AtomicReference<@Nullable Subscription> upstream;
      private final int prefetch;
      private final int prefetchThreshold;
      private int upstreamWindow;

      JacksonSubscriber(
          ObjectMapper mapper,
          TypeReference<T> type,
          JsonParser parser,
          EncodingProcessor processor) {
        this.mapper = mapper;
        this.objReader = mapper.readerFor(mapper.constructType(type.type()));
        this.parser = parser;
        this.processor = processor;
        feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
        jsonBuffer = new TokenBuffer(this.parser);
        valueFuture = new CompletableFuture<>();
        upstream = new AtomicReference<>();
        prefetch = FlowSupport.prefetch();
        prefetchThreshold = FlowSupport.prefetchThreshold();
      }

      @Override
      public CompletionStage<T> getBody() {
        return valueFuture;
      }

      @Override
      public void onSubscribe(Subscription subscription) {
        requireNonNull(subscription);
        if (upstream.compareAndSet(null, subscription)) {
          upstreamWindow = prefetch;
          subscription.request(prefetch);
        } else {
          subscription.cancel();
        }
      }

      @Override
      public void onNext(List<ByteBuffer> item) {
        requireNonNull(item);
        try {
          byte[] bytes = collectBytes(processor.process(item, false));
          feeder.feedInput(bytes, 0, bytes.length);
          flushParser();
        } catch (Throwable ioe) {
          complete(ioe, true);
          return;
        }

        // See if more should be requested w.r.t prefetch logic
        Subscription s = upstream.get();
        if (s != null) {
          int update = upstreamWindow - 1;
          if (update <= prefetchThreshold) {
            upstreamWindow = prefetch;
            s.request(prefetch - update);
          } else {
            upstreamWindow = update;
          }
        }
      }

      @Override
      public void onError(Throwable throwable) {
        requireNonNull(throwable);
        complete(throwable, false);
      }

      @Override
      public void onComplete() {
        complete(null, false);
      }

      private void complete(@Nullable Throwable error, boolean cancelUpstream) {
        if (cancelUpstream) {
          Subscription s = upstream.getAndSet(FlowSupport.NOOP_SUBSCRIPTION);
          if (s != null) {
            s.cancel();
          }
        } else {
          upstream.set(FlowSupport.NOOP_SUBSCRIPTION);
        }
        if (error != null) {
          valueFuture.completeExceptionally(error);
        } else {
          try {
            // flush processor
            List<ByteBuffer> flushed = processor.process(EMPTY_BUFFER_LIST, true);
            if (!flushed.isEmpty() && flushed != EMPTY_BUFFER_LIST) {
              byte[] bytes = collectBytes(flushed);
              feeder.feedInput(bytes, 0, bytes.length);
            }
            feeder.endOfInput();
            flushParser(); // Flush parser after endOfInput event
            valueFuture.complete(objReader.readValue(jsonBuffer.asParser(mapper)));
          } catch (Throwable ioe) {
            valueFuture.completeExceptionally(ioe);
          }
        }
      }

      private void flushParser() throws IOException {
        JsonToken token;
        while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
          jsonBuffer.copyCurrentEvent(parser);
        }
      }

      private byte[] collectBytes(List<ByteBuffer> buffs) {
        int size = buffs.stream().mapToInt(ByteBuffer::remaining).sum();
        byte[] bytes = new byte[size];
        int offset = 0;
        for (ByteBuffer buff : buffs) {
          int remaining = buff.remaining();
          buff.get(bytes, offset, buff.remaining());
          offset += remaining;
        }
        return bytes;
      }
    }
  }
}
