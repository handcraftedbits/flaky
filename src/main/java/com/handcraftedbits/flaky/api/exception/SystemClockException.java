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
package com.handcraftedbits.flaky.api.exception;

/**
 * An exception that is thrown whenever the system clock drifts backwards during ID generation. This can occur because
 * e.g., the system clock was updated with the current NTP time.
 */

public class SystemClockException extends Exception {
     private static final long serialVersionUID = 1L;

     private final long nextTimestamp;

     /**
      * Creates a SystemClockException object.
      *
      * @param nextTimestamp a long containing the timestamp which Flaky considers current.
      */

     public SystemClockException (final long nextTimestamp) {
          super(String.format("system clock is running backwards; wait until %d to continue generating IDs",
               nextTimestamp));

          this.nextTimestamp = nextTimestamp;
     }

     /**
      * Retrieves the timestamp which Flaky considers current. Clients should sleep until this time to continue
      * generating IDs.
      *
      * @return a long containing the timestamp which Flaky considers current.
      */

     public long getNextTimestamp () {
          return this.nextTimestamp;
     }
}
