# A Method Handle Made at Run Time Was Interpreted

`2026-09-30`

Spring PetClinic, a Spring Boot 4 service, answers `/vets`, a list of veterinarians as JSON, at 36,936 requests a second as Oracle's profile-guided binary and at 35,756 as CrucibleVM's.
JDK Flight Recorder execution samples of both under load put a fifth of the time under one call: 20.6% of Oracle's samples and 22.7% of CrucibleVM's are in method-handle code called from `BeanPropertyWriter.get`, where Jackson 3 reads a property of the object it writes.

## Why

Jackson 3 keeps each getter as a method handle of type `(Object)Object` and calls it with `invokeExact`.
It makes that handle when it first sees a class, at run time: the getter's direct handle, adapted by `asType`.

A native image calls a direct method handle through the compiled accessor of its member.
The adapter that `asType` makes has no member of its own.
`MethodHandleImpl.makePairwiseConvert` builds it as a bound method handle with a lambda form that casts the arguments and the return value, and the image has no compiled code for a lambda form made at run time, so `invokeBasic` interprets it.
It does so for every property of every object written, argument by argument.

## The Change

When `makePairwiseConvert` adapts a direct method handle and every conversion is a reference cast, with a return value that is the same or is returned as an object, the adapter is told its target.
`invokeBasic` then checks the casts itself, throwing the `ClassCastException` that `Class.cast` throws with the same message, and calls the target, which goes through its compiled accessor.
Any other adapter is interpreted as before.

The change is in the substitutions of `java.lang.invoke.MethodHandle` and `java.lang.invoke.MethodHandleImpl`.
A program that makes such adapters at run time, around virtual, interface, static, and constructor handles, and calls them with the right arguments and with arguments of the wrong type, prints the same twelve lines as a native image and on the JVM.

## What It Is Worth

Spring PetClinic, three rounds, oha with 16 connections on four processors and the service on four others, two builds of the image with the change:

| | `/vets`, requests a second | `/`, requests a second | `/vets`, median latency |
| --- | --- | --- | --- |
| Oracle GraalVM, profile-guided | 36,936 | 5,883 | 0.39 ms |
| CrucibleVM, before | 35,756 | 6,250 | 0.39 ms |
| CrucibleVM, with the change | **44,188** | **6,347** | **0.31 ms** |

The second build ran 43,912 requests a second on `/vets`.
The JSON endpoint is 24% faster, and 20% ahead of Oracle's binary, which interprets the same adapter.
Any service that writes JSON with Jackson 3 goes through this call for every property it writes.
