/**
 * Copyright (C) 2018 HandcraftedBits
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.handcraftedbits.flaky.api.generator;

import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import com.handcraftedbits.flaky.api.exception.SystemClockException;

class FlakyIdTest {
     @Test
     void testBuilderInvalidMaximumNodeLength () {
          Assertions.assertThrows(IllegalArgumentException.class, () -> {
               new FlakyId.Builder().withNodeLength(FlakyId.LENGTH_NODE_MAX + 1L);
          }, String.format("node length must be between %d and %d bits", FlakyId.LENGTH_NODE_MIN,
               FlakyId.LENGTH_NODE_MAX));
     }

     @Test
     void testBuilderInvalidMaximumSequenceLength () {
          Assertions.assertThrows(IllegalArgumentException.class, () -> {
               new FlakyId.Builder().withSequenceLength(FlakyId.LENGTH_TOTAL + 1L);
          }, String.format("sequence length must be between %d and %d bits", FlakyId.LENGTH_SEQUENCE_MIN,
               FlakyId.LENGTH_TOTAL));
     }

     @Test
     void testBuilderInvalidMinimumNodeLength () {
          Assertions.assertThrows(IllegalArgumentException.class, () -> {
               new FlakyId.Builder().withNodeLength(FlakyId.LENGTH_NODE_MIN - 1L);
          }, String.format("node length must be between %d and %d bits", FlakyId.LENGTH_NODE_MIN,
               FlakyId.LENGTH_NODE_MAX));
     }

     @Test
     void testBuilderInvalidMinimumSequenceLength () {
          Assertions.assertThrows(IllegalArgumentException.class, () -> {
               new FlakyId.Builder().withSequenceLength(FlakyId.LENGTH_SEQUENCE_MIN - 1L);
          }, String.format("sequence length must be between %d and %d bits", FlakyId.LENGTH_SEQUENCE_MIN,
               FlakyId.LENGTH_TOTAL));
     }

     @Test
     void testBuilderNodeNegative () {
          Assertions.assertThrows(IllegalArgumentException.class, () -> {
               new FlakyId.Builder().withNode(-1L);
          }, "node value must not be less than zero");
     }

     @Test
     void testBuilderNodeTooLarge () {
          Assertions.assertThrows(IllegalArgumentException.class, () -> {
               new FlakyId.Builder().withNodeLength(1L).withNode(2L);
          }, "node value 2 does not fit within desired node length of 1 bit");
     }

     @Test
     void testBuilderNodeTooLargePlural () {
          Assertions.assertThrows(IllegalArgumentException.class, () -> {
               new FlakyId.Builder().withNode(4L).withNodeLength(2L);
          }, "node value 4 does not fit within desired node length of 2 bits");
     }

     @Test
     void testBuilderSimple () {
          Assertions.assertNotNull(new FlakyId.Builder().withNode(1L).withNodeLength(11L).withSequenceLength(11L)
               .withEpoch(1L).build());
     }

     @Test
     void testBuilderValidNodeLength () {
          new FlakyId.Builder().withNodeLength(FlakyId.LENGTH_NODE_MIN).withNodeLength(FlakyId.LENGTH_NODE_MAX);
     }

     @Test
     void testBuilderValidSequenceLength () {
          new FlakyId.Builder().withSequenceLength(FlakyId.LENGTH_SEQUENCE_MIN).withSequenceLength(
               FlakyId.LENGTH_TOTAL);
     }

     @Test
     void testGenerateIdClockException () {
          final FlakyId generator = Mockito.spy(new FlakyId.Builder().build());

          Mockito.when(generator.getCurrentTimestamp()).thenReturn(-1L);

          try {
               generator.generateId();

               Assertions.fail("generateId() should have failed with a SystemClockException");
          }

          catch (final SystemClockException e) {
               Assertions.assertEquals(e.getMessage(), "system clock is running backwards; wait until 0 to continue " +
                    "generating IDs");
               Assertions.assertEquals(e.getNextTimestamp(), 0L);
          }
     }

     @Test
     void testGenerateIdOverflow () throws Throwable {
          final long expectedNode = 65535L;
          final long expectedTimestamp = 1234L;
          final FlakyId generator;
          long id;
          final long nodeLength = FlakyId.LENGTH_NODE_MAX;
          final long sequenceLength = FlakyId.LENGTH_TOTAL - nodeLength;

          // With a 6 bit sequence length, mock out FlakyId.getCurrentTimestamp() such that the first 64 invocations
          // give a constant timestamp. This will cause an overflow. Two calls later will yield a larger timestamp and
          // let us proceed.

          generator = Mockito.spy(new FlakyId.Builder().withEpoch(0L).withSequenceLength(sequenceLength)
               .withNode(expectedNode).build());

          Mockito.when(generator.getCurrentTimestamp()).thenAnswer(new OverflowAnswer(expectedTimestamp));

          for (long i = 0; i < 64L; ++i) {
               id = generator.generateId();

               Assertions.assertEquals(id >> FlakyId.LENGTH_TOTAL, expectedTimestamp);
               Assertions.assertEquals((id >> (FlakyId.LENGTH_TOTAL - nodeLength)) & ~(-1 << nodeLength), expectedNode);
               Assertions.assertEquals(id & ~(-1 << sequenceLength), i);
          }

          id = generator.generateId();

          Assertions.assertEquals(id >> FlakyId.LENGTH_TOTAL, expectedTimestamp + 1L);
          Assertions.assertEquals((id >> (FlakyId.LENGTH_TOTAL - nodeLength)) & ~(-1 << nodeLength), expectedNode);
          Assertions.assertEquals(id & ~(-1 << sequenceLength), 0);
     }

     @Test
     void testGenerateIdSafeWait () {
          long currentTime = 0L;
          final FlakyId generator = Mockito.spy(new FlakyId.Builder().build());
          final long start = System.currentTimeMillis();
          final long waitTime = 100L;

          // Make the system clock drift backwards and expect a 100ms wait to generate an ID.

          Mockito.when(generator.getCurrentTimestamp()).thenAnswer(new WaitAnswer(start, waitTime));

          generator.generateIdSafe();

          // This call should result in a wait.

          generator.generateIdSafe();

          currentTime = System.currentTimeMillis();

          Assertions.assertTrue(currentTime - start >= waitTime);
     }

     @Test
     void testGenerateIdSimple () throws Throwable {
          final long expectedNode = 31L;
          final long expectedTimestamp = 1234L;
          final FlakyId generator;
          final long nodeLength = 5L;

          // Expect to find the correct timestamp, node, and sequence value. We'll use a custom node length as well.

          generator = Mockito.spy(new FlakyId.Builder().withEpoch(0L).withNodeLength(nodeLength)
               .withNode(expectedNode).build());

          Mockito.when(generator.getCurrentTimestamp()).thenReturn(expectedTimestamp);

          for (long i = 0; i < 10L; ++i) {
               final long id = generator.generateId();

               Assertions.assertEquals(id >> FlakyId.LENGTH_TOTAL, expectedTimestamp);
               Assertions.assertEquals((id >> (FlakyId.LENGTH_TOTAL - nodeLength)) & ~(-1 << nodeLength), expectedNode);
               Assertions.assertEquals(id & ~(-1 << FlakyId.LENGTH_TOTAL - nodeLength), i);
          }
     }

     @Test
     void testGenerateIdWait () throws Throwable {
          long currentTime = 0L;
          final FlakyId generator = Mockito.spy(new FlakyId.Builder().build());
          final long start = System.currentTimeMillis();
          final long waitTime = 100L;

          // Expect SystemClockException to be thrown when the clock drifts backwards and a 100ms wait to generate an
          // ID.

          Mockito.when(generator.getCurrentTimestamp()).thenAnswer(new WaitAnswer(start, waitTime));

          try {
               generator.generateId();
          }

          catch (final SystemClockException e) {
               Assertions.fail("generateId() should not have thrown an exception");
          }

          try {
               generator.generateId();

               Assertions.fail("generateId() should have thrown an exception");
          }

          catch (final SystemClockException e) {
               Assertions.assertEquals(e.getNextTimestamp(), start + waitTime);

               while (currentTime < e.getNextTimestamp()) {
                    LockSupport.parkUntil(e.getNextTimestamp());

                    currentTime = System.currentTimeMillis();
               }
          }

          Assertions.assertTrue(currentTime - start >= waitTime);

          // ID generation should succeed now.

          Mockito.reset(generator);

          generator.generateId();
     }

     private static final class OverflowAnswer implements Answer<Long> {
          private final long expectedTimestamp;
          private int count;

          private OverflowAnswer (final long expectedTimestamp) {
               this.expectedTimestamp = expectedTimestamp;
          }

          @Override
          public Long answer (final InvocationOnMock invocation) throws Throwable {
               if (this.count < 66) {
                    ++this.count;

                    return this.expectedTimestamp;
               }

               return (this.expectedTimestamp + 1);
          }
     }

     private static final class WaitAnswer implements Answer<Long> {
          private int count;
          private final long initialTimestamp;
          private final long waitTime;

          private WaitAnswer (final long initialTimestamp, final long waitTime) {
               this.count = 0;
               this.initialTimestamp = initialTimestamp;
               this.waitTime = waitTime;
          }

          @Override
          public Long answer (final InvocationOnMock invocation) throws Throwable {
               switch (this.count++) {
                    case 0:
                         return (this.initialTimestamp + this.waitTime);

                    case 1:
                         return this.initialTimestamp;

                    default:
                         return System.currentTimeMillis();
               }
          }
     }
}
