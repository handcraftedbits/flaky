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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.handcraftedbits.flaky.api.exception.SystemClockException;

/**
 * The class used to generate Flaky IDs, which are an implementation of
 * <a href="https://blog.twitter.com/engineering/en_us/a/2010/announcing-snowflake.html" target="_blank"> Twitter
 * Snowflake</a> IDs. Flaky follows the reference implementation with respect to the timestamp length (41 bits), but
 * allows the node and sequence components to have variable length.
 */

public final class FlakyId {
     static final long LENGTH_NODE_MAX = 16L;
     static final long LENGTH_NODE_MIN = 0L;
     static final long LENGTH_SEQUENCE_MIN = FlakyId.LENGTH_TOTAL - FlakyId.LENGTH_NODE_MAX;
     static final long LENGTH_TOTAL = FlakyId.LENGTH_NODE_DEFAULT + FlakyId.LENGTH_SEQUENCE_DEFAULT;

     private static final long EPOCH_DEFAULT = 1480323660000L;
     private static final long LENGTH_NODE_DEFAULT = 10L;
     private static final long LENGTH_SEQUENCE_DEFAULT = 12L;

     private static final Logger logger = LoggerFactory.getLogger(FlakyId.class);

     private final long epoch;
     private volatile long lastTimestamp;
     private final long node;
     private final long nodeShift;
     private volatile long sequence;
     private final long sequenceMask;
     private final long timestampShift;

     private FlakyId (final long epoch, final long node, final long nodeLength, final long sequenceLength) {
          this.epoch = epoch;
          this.node = node;
          this.nodeShift = sequenceLength;
          this.sequenceMask = ~(-1 << sequenceLength);
          this.timestampShift = nodeLength + sequenceLength;
     }

     /**
      * Generates a Flaky ID.
      *
      * @return a long containing a new Flaky ID.
      * @throws SystemClockException if the system clock drifts backwards while generating the ID.
      */

     public synchronized long generateId () throws SystemClockException {
          final long timestamp = getCurrentTimestamp();

          if (timestamp < this.lastTimestamp) {
               throw new SystemClockException(this.lastTimestamp);
          }

          return generateIdCommon(timestamp);
     }

     private long generateIdCommon (final long providedTimestamp) {
          long timestamp = providedTimestamp;

          if (timestamp == this.lastTimestamp) {
               this.sequence = (this.sequence + 1) & this.sequenceMask;

               if (this.sequence == 0L) {
                    // Overflow, so wait until we can generate another ID (i.e., a millisecond or less).

                    timestamp = getCurrentTimestamp();

                    while (timestamp <= this.lastTimestamp) {
                         // Doing this in a loop because LockSupport.parkUntil() can return early for any reason, so we
                         // can't assume we've waited long enough.

                         LockSupport.parkUntil(this.lastTimestamp + 1);

                         timestamp = getCurrentTimestamp();
                    }
               }
          }

          this.lastTimestamp = timestamp;

          return (((timestamp - this.epoch) << this.timestampShift) | (this.node << this.nodeShift) | this.sequence);
     }

     /**
      * Generates a Flaky ID in a safe manner by always waiting if the system clock drifts backwards. Be aware that
      * there is no limit on how long this method will wait.
      *
      * @return a long containing a new Flaky ID.
      */

     public synchronized long generateIdSafe () {
          long timestamp = getCurrentTimestamp();

          if (timestamp < this.lastTimestamp) {
               FlakyId.logger.warn(String.format("system clock has drifted backwards; waiting %dms",
                    (this.lastTimestamp - timestamp)));

               while (timestamp < this.lastTimestamp) {
                    // Doing this in a loop because LockSupport.parkUntil() can return early for any reason, so we
                    // can't assume we've waited long enough.

                    LockSupport.parkUntil(this.lastTimestamp);

                    timestamp = getCurrentTimestamp();
               }
          }

          return generateIdCommon(timestamp);
     }

     long getCurrentTimestamp () {
          return System.currentTimeMillis();
     }

     /**
      * Used to configure a Flaky ID generator.
      */

     public static final class Builder {
          private long epoch;
          private long node;
          private long nodeLength;
          private long sequenceLength;

          /**
           * Creates a new Flaky ID generator builder.
           */

          public Builder () {
               this.epoch = FlakyId.EPOCH_DEFAULT;
               this.nodeLength = FlakyId.LENGTH_NODE_DEFAULT;
               this.sequenceLength = FlakyId.LENGTH_SEQUENCE_DEFAULT;
          }

          /**
           * Creates a Flaky ID generator with default epoch, node length (10 bits) and sequence length (12 bits).
           *
           * @return a {@link FlakyId} object containing a new Flaky ID generator.
           */

          public FlakyId build () {
               return new FlakyId(this.epoch, this.node, this.nodeLength, this.sequenceLength);
          }

          private void checkNode (final long node, final long newNodeLength) {
               if (node > ~(-1 << newNodeLength)) {
                    throw new IllegalArgumentException(String.format("node value %d does not fit within desired node " +
                         "length of %d bit%s", node, newNodeLength, newNodeLength == 1 ? "" : "s"));
               }
          }

          /**
           * Sets the epoch for the Flaky ID generator.
           *
           * @param epoch a long containing the epoch to use.
           * @return a {@link Builder} instance containing this builder for chaining purposes.
           */

          public Builder withEpoch (final long epoch) {
               this.epoch = epoch;

               return this;
          }

          /**
           * Sets the node value for the Flaky ID generator.
           *
           * @param node a long containing the node value to use.
           * @return a {@link Builder} instance containing this builder for chaining purposes.
           * @throws IllegalArgumentException if a negative node value or a node value that does not fit within the
           *              current node length is provided.
           */

          public Builder withNode (final long node) {
               if (node < 0L) {
                    throw new IllegalArgumentException("node value must not be less than zero");
               }

               checkNode(node, this.nodeLength);

               this.node = node;

               return this;
          }

          /**
           * Sets the node length for the Flaky ID generator.
           *
           * @param nodeLength a long containing the node length to use.
           * @return a {@link Builder} instance containing this builder for chaining purposes.
           * @throws IllegalArgumentException if an invalid node length (less than 0 or greater than 16) is provided or
           *              if the current node value will not fit within the new node length.
           */

          public Builder withNodeLength (final long nodeLength) {
               if ((nodeLength < FlakyId.LENGTH_NODE_MIN) || (nodeLength > FlakyId.LENGTH_NODE_MAX)) {
                    throw new IllegalArgumentException(String.format("node length must be between %d and %d bits",
                         FlakyId.LENGTH_NODE_MIN, FlakyId.LENGTH_NODE_MAX));
               }

               checkNode(this.node, nodeLength);

               this.nodeLength = nodeLength;
               this.sequenceLength = FlakyId.LENGTH_TOTAL - nodeLength;

               return this;
          }

          /**
           * Sets the sequence length for the Flaky ID generator.
           *
           * @param sequenceLength a long containing the sequence length to use.
           * @return a {@link Builder} instance containing this builder for chaining purposes.
           * @throws IllegalArgumentException if an invalid sequence length (less than 6 or greater than 22) is provided
           *              of if the current node value will not fit within the adjust node length.
           */

          public Builder withSequenceLength (final long sequenceLength) {
               if ((sequenceLength < FlakyId.LENGTH_SEQUENCE_MIN) || (sequenceLength > FlakyId.LENGTH_TOTAL)) {
                    throw new IllegalArgumentException(String.format("sequence length must be between %d and %d bits",
                         FlakyId.LENGTH_SEQUENCE_MIN, FlakyId.LENGTH_TOTAL));
               }

               checkNode(this.node, FlakyId.LENGTH_TOTAL - sequenceLength);

               this.nodeLength = FlakyId.LENGTH_TOTAL - sequenceLength;
               this.sequenceLength = sequenceLength;

               return this;
          }
     }
}
