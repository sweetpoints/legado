// ignore_for_file: non_constant_identifier_names
// Native ABI of src/source_v8.cpp. Kept explicit to make ownership visible.
import 'dart:ffi';

@Native<Pointer<Void> Function(Int32, Int32)>()
external Pointer<Void> sv8_create(int timeoutMs, int heapMb);
@Native<
  Void Function(Pointer<Void>, Pointer<Char>, Pointer<Char>, Pointer<Char>)
>()
external void sv8_start(
  Pointer<Void> runtime,
  Pointer<Char> code,
  Pointer<Char> vars,
  Pointer<Char> prelude,
);
@Native<Pointer<Char> Function(Pointer<Void>)>()
external Pointer<Char> sv8_poll(Pointer<Void> runtime);
@Native<Void Function(Pointer<Void>, Int32, Pointer<Char>, Int32)>()
external void sv8_resolve(
  Pointer<Void> runtime,
  int id,
  Pointer<Char> value,
  int rejected,
);
@Native<Void Function(Pointer<Void>)>()
external void sv8_cancel(Pointer<Void> runtime);
@Native<Void Function(Pointer<Void>)>()
external void sv8_destroy(Pointer<Void> runtime);
@Native<Void Function(Pointer<Char>)>()
external void sv8_free(Pointer<Char> value);
@Native<Pointer<Char> Function()>()
external Pointer<Char> sv8_version();
@Native<Pointer<Char> Function(Pointer<Void>)>()
external Pointer<Char> sv8_sync_poll(Pointer<Void> runtime);
@Native<Void Function(Pointer<Void>, Int32, Pointer<Char>, Int32)>()
external void sv8_sync_reply(
  Pointer<Void> runtime,
  int id,
  Pointer<Char> value,
  int rejected,
);
