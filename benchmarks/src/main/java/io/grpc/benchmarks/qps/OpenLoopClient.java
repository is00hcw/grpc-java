/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.benchmarks.qps;

import static com.google.common.base.Preconditions.checkNotNull;
import static grpc.testing.TestServiceGrpc.TestServiceStub;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.CLIENT_PAYLOAD;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.CONNECTION_WINDOW;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.DURATION;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.HOST;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.OKHTTP;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.PORT;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.SAVE_HISTOGRAM;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.SERVER_PAYLOAD;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.STREAM_WINDOW;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.TARGET_QPS;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.TESTCA;
import static io.grpc.benchmarks.qps.ClientConfiguration.Builder.Option.TLS;
import static io.grpc.benchmarks.qps.ClientConfiguration.HISTOGRAM_MAX_VALUE;
import static io.grpc.benchmarks.qps.ClientConfiguration.HISTOGRAM_PRECISION;
import static io.grpc.benchmarks.qps.ClientUtil.newRequest;
import static io.grpc.benchmarks.qps.ClientUtil.saveHistogram;

import grpc.testing.Qpstest;
import grpc.testing.Qpstest.SimpleRequest;
import grpc.testing.TestServiceGrpc;
import io.grpc.Channel;
import io.grpc.ChannelImpl;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.HdrHistogram.AtomicHistogram;
import org.HdrHistogram.Histogram;

import java.util.Random;
import java.util.concurrent.Callable;

/**
 * Tries to generate traffic that closely resembles user-generated RPC traffic. This is done using
 * a Poisson Process to average at a target QPS and the delays between calls are randomized using
 * an exponential variate.
 *
 * @see <a href="http://en.wikipedia.org/wiki/Poisson_process">Poisson Process</a>
 * @see <a href="http://en.wikipedia.org/wiki/Exponential_distribution">Exponential Distribution</a>
 */
public class OpenLoopClient {

  private final ClientConfiguration config;

  public OpenLoopClient(ClientConfiguration config) {
    this.config = config;
  }

  /**
   * Comment for checkstyle.
   */
  public static void main(String... args) throws Exception {
    ClientConfiguration.Builder configBuilder =
        ClientConfiguration.newBuilder()
            .addOptions(PORT, HOST, TARGET_QPS, CLIENT_PAYLOAD, SERVER_PAYLOAD, TLS, TESTCA)
            .addOptions(OKHTTP, DURATION, SAVE_HISTOGRAM, CONNECTION_WINDOW, STREAM_WINDOW);
    ClientConfiguration config;
    try {
      config = configBuilder.build(args);
    } catch (Exception e) {
      System.out.println(e.getMessage());
      configBuilder.printUsage();
      return;
    }
    OpenLoopClient client = new OpenLoopClient(config);
    client.run();
  }

  /**
   * Start the open loop client.
   */
  public void run() throws Exception {
    if (config == null) {
      return;
    }
    config.channels = 1;
    config.directExecutor = true;
    Channel ch = ClientUtil.newChannel(config);
    SimpleRequest req = newRequest(config);
    LoadGenerationWorker worker =
        new LoadGenerationWorker(ch, req, config.targetQps, config.duration);
    final long start = System.nanoTime();
    Histogram histogram = worker.call();
    final long end = System.nanoTime();
    printStats(histogram, end - start);
    if (config.histogramFile != null) {
      saveHistogram(histogram, config.histogramFile);
    }
    ((ChannelImpl) ch).shutdown();
  }

  private void printStats(Histogram histogram, long elapsedTime) {
    long latency50 = histogram.getValueAtPercentile(50);
    long latency90 = histogram.getValueAtPercentile(90);
    long latency95 = histogram.getValueAtPercentile(95);
    long latency99 = histogram.getValueAtPercentile(99);
    long latency999 = histogram.getValueAtPercentile(99.9);
    long latencyMax = histogram.getValueAtPercentile(100);
    long queriesPerSecond = histogram.getTotalCount() * 1000000000L / elapsedTime;

    StringBuilder values = new StringBuilder();
    values.append("Server Payload Size:            ").append(config.serverPayload).append('\n')
          .append("Client Payload Size:            ").append(config.clientPayload).append('\n')
          .append("50%ile Latency (in micros):     ").append(latency50).append('\n')
          .append("90%ile Latency (in micros):     ").append(latency90).append('\n')
          .append("95%ile Latency (in micros):     ").append(latency95).append('\n')
          .append("99%ile Latency (in micros):     ").append(latency99).append('\n')
          .append("99.9%ile Latency (in micros):   ").append(latency999).append('\n')
          .append("Maximum Latency (in micros):    ").append(latencyMax).append('\n')
          .append("Actual QPS:                     ").append(queriesPerSecond).append('\n')
          .append("Target QPS:                     ").append(config.targetQps).append('\n');
    System.out.println(values);
  }

  static class LoadGenerationWorker implements Callable<Histogram> {
    final Histogram histogram = new AtomicHistogram(HISTOGRAM_MAX_VALUE, HISTOGRAM_PRECISION);
    final TestServiceStub stub;
    final SimpleRequest request;
    final Random rnd;
    final int targetQps;
    final long numRpcs;

    LoadGenerationWorker(Channel channel, SimpleRequest request, int targetQps, int duration) {
      stub = TestServiceGrpc.newStub(checkNotNull(channel, "channel"));
      this.request = checkNotNull(request, "request");
      this.targetQps = targetQps;
      numRpcs = targetQps * duration;
      rnd = new Random();
    }

    /**
     * Discuss waiting strategy between calls. Sleeping seems to be very inaccurate
     * (see below). On the other hand calling System.nanoTime() a lot (especially from
     * different threads seems to impact its accuracy
     * http://shipilev.net/blog/2014/nanotrusting-nanotime/
     * On my system the overhead of LockSupport.park(long) seems to average at ~55 micros.
     * // Try to sleep for 1 nanosecond and measure how long it actually takes.
     * long start = System.nanoTime();
     * int i = 0;
     * while (i < 10000) {
     *   LockSupport.parkNanos(1);
     *   i++;
     * }
     * long end = System.nanoTime();
     * System.out.println((end - start) / 10000);
     */
    @Override
    public Histogram call() throws Exception {
      long now = System.nanoTime();
      long nextRpc = now;
      long i = 0;
      while (i < numRpcs) {
        now = System.nanoTime();
        if (nextRpc - now <= 0) {
          // TODO: Add option to print how far we have been off from the target delay in micros.
          nextRpc += nextDelay(targetQps);
          newRpc(stub);
          i++;
        }
      }

      waitForRpcsToComplete(1);

      return histogram;
    }

    private void newRpc(TestServiceStub stub) {
      stub.unaryCall(request, new StreamObserver<Qpstest.SimpleResponse>() {

        private final long start = System.nanoTime();

        @Override
        public void onValue(Qpstest.SimpleResponse value) {
        }

        @Override
        public void onError(Throwable t) {
          Status status = Status.fromThrowable(t);
          System.err.println("Encountered an error in unaryCall. Status is " + status);
          t.printStackTrace();
        }

        @Override
        public void onCompleted() {
          final long end = System.nanoTime();
          histogram.recordValue((end - start) / 1000);
        }
      });
    }

    private void waitForRpcsToComplete(int duration) throws InterruptedException {
      long now = System.nanoTime();
      long end = now + duration * 1000 * 1000 * 1000;
      while (histogram.getTotalCount() < numRpcs && now < end) {
        now = System.nanoTime();
      }
    }

    private long nextDelay(int targetQps) {
      // Smallest value so that (1 - epsilon) != 1
      // See http://en.wikipedia.org/wiki/Machine_epsilon
      final double epsilon = 1.11E-16;
      double seconds = -Math.log(Math.max(rnd.nextDouble(), epsilon)) / targetQps;
      double nanos = seconds * 1000 * 1000 * 1000;
      return Math.round(nanos);
    }
  }
}
