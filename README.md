# Flaky [![Maven](https://img.shields.io/maven-metadata/v/http/central.maven.org/maven2/com/handcraftedbits/web/flaky/maven-metadata.xml.svg)](https://mvnrepository.com/artifact/com.handcraftedbits.web/flaky/1.0.1) [![Build Status](https://travis-ci.org/handcraftedbits/flaky.svg?branch=master)](https://travis-ci.org/handcraftedbits/flaky) [![Coverage Status](https://coveralls.io/repos/github/handcraftedbits/flaky/badge.svg)](https://coveralls.io/github/handcraftedbits/flaky)

A Java implementation of [Twitter Snowflake](https://blog.twitter.com/engineering/en_us/a/2010/announcing-snowflake.html).
The implementation sticks closely to the specification with the exception that the node and sequence lengths can be
adjusted instead of staying fixed at the default values of 10 and 12 bits, respectively.

In short: Flaky will generate unique, increasing 64-bit values, which are perfect for database entity IDs.  Furthermore,
these values will never collide, even in a distributed environment, as long as the machines generating the IDs maintain
different _node values_.

# Concepts

## Node Value

An integer used to identify a particular machine generating IDs using Flaky.  Can be between 0 (meaning there is
only a single machine generating IDs) and 16 (meaning there are up to 65535 machines generating IDs) bits in length.

## Sequence Value

An increasing value that rolls over to 0 every millisecond.  Can be between 6 and 22 bits in length.

## How an ID is Generated

A Flaky ID is a 64-bit value that looks like this:

```
+---------------------+------------------------+----------------------------+
| Timestamp (41 bits) | Node value (0-16 bits) | Sequence Value (6-22 bits) |
+---------------------+------------------------+----------------------------+
```

The **timestamp** is set to the number of milliseconds that have elapsed since the **epoch**, which is configurable and
defaults to `2016-11-28`.  Since this value is 41 bits in length, Flaky IDs will be valid for a little over 69 years
after the epoch.

The **node value** remains static and is dictated by a user-configurable value (with a default value of 0).  This value
should always remain the same when Flaky is invoked on the same machine.

The **sequence value** increments on every call and rolls over to 0 every millisecond.

With default settings, this means that each node can generate 4096 unique IDs per millisecond for nearly 70 years!  You
can generate more or fewer IDs per millisecond by tweaking the sequence length.

# Requirements

Flaky requires Java 8 or later.

# Usage

Add the following dependency in your `pom.xml` file:

```xml
<dependency>
  <groupId>com.handcraftedbits.web</groupId>
  <artifactId>flaky</artifactId>
  <version>1.0.1</version>
</dependency>
```

Then, create a `FlakyId` instance:

```java
// Use defaults: 10-bit node length, 12 bit sequence length, node value = 0.
FlakyId generator = new FlakyId.Builder().build();

// Use defaults with a specific node value.
generator = new FlakyId.Builder().withNode(1L).build();

// Use a shorter node value length, which gives a much longer sequence length.
generator = new FlakyId.Builder().withNodeLength(4L).build();

// Use a custom epoch and longer sequence length.
generator = new FlakyId.Builder().withEpoch(System.currentTimeMillis()).withSequenceLength(16L).build();
```

Then, generate away!

```java
try {
     long timestamp = generator.generateId();
     // ...
}

catch (SystemClockException e) {
     // Give up?  Or wait:

     java.util.concurrent.locks.LockSupport.parkUntil(e.getNextTimestamp());
}
```

Unfortunately, it's not possible to generate IDs without having to deal with exceptions.  There is a very real
possibility that the machine's system clock will drift backwards from time to time, such as when the machine receives an
updated time from its NTP server.  It's not possible for Flaky to handle this situation without creating the possibility
of collisions, so instead `generateId()` will throw `SystemClockException` in this case.  You will not be able to
continue generating IDs until the system clock catches up with the last recorded value, which is provided via
`SystemClockException.getNextTimestamp()`.  Whenever a `SystemClockException` is thrown your best course of
action is to simply wait until the system clock catches up.
