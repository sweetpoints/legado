// Linux-only ownership adapters for the fixed V8 SDK's hidden PartitionAlloc.
// No malloc/free/new/delete symbols are exported or replaced by this file.
// libc-owned handles (FILE*, DIR*, locale_t) keep their libc close functions.
#include <cerrno>
#include <climits>
#include <cstdarg>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

extern "C" {
// These real functions allocate in glibc. __libc_free is intentionally used
// only for their original returned buffers, never for an embedder allocation.
void sv8_glibc_free(void*) noexcept;
// The SDK itself also defines an unversioned __libc_free. A versioned symbol
// reference is essential: an ordinary __libc_free call would bind to that PA
// alias and repeat the ownership bug. These are glibc's original ABI versions.
#if defined(__aarch64__)
__asm__(".symver sv8_glibc_free,__libc_free@GLIBC_2.17");
#elif defined(__x86_64__)
__asm__(".symver sv8_glibc_free,__libc_free@GLIBC_2.2.5");
#else
#error Unsupported Linux allocator boundary architecture
#endif
char* __real_realpath(const char*, char*) noexcept;
char* __real_getcwd(char*, size_t) noexcept;
int __real_vasprintf(char**, const char*, va_list) noexcept;
char** __real_backtrace_symbols(void* const*, int) noexcept;

static char* copy_libc_string(char* original, size_t allocation_size = 0) {
  if (!original) return nullptr;  // Preserve the real function's errno.
  const int saved_errno = errno;
  const size_t length = std::strlen(original) + 1;
  if (allocation_size < length) allocation_size = length;
  char* result = static_cast<char*>(std::malloc(allocation_size));
  if (result) std::memcpy(result, original, length);
  sv8_glibc_free(original);
  errno = result ? saved_errno : ENOMEM;
  return result;
}

char* __wrap_strdup(const char* text) noexcept {
  const int saved_errno = errno;
  const size_t size = std::strlen(text) + 1;
  char* result = static_cast<char*>(std::malloc(size));
  if (result) std::memcpy(result, text, size);
  errno = result ? saved_errno : ENOMEM;
  return result;
}

char* __wrap_strndup(const char* text, size_t maximum) noexcept {
  const int saved_errno = errno;
  const size_t length = ::strnlen(text, maximum);
  if (length == SIZE_MAX) { errno = ENOMEM; return nullptr; }
  char* result = static_cast<char*>(std::malloc(length + 1));
  if (result) {
    std::memcpy(result, text, length);
    result[length] = '\0';
  }
  errno = result ? saved_errno : ENOMEM;
  return result;
}

char* __wrap_realpath(const char* path, char* buffer) noexcept {
  char* result = __real_realpath(path, buffer);
  return buffer ? result : copy_libc_string(result);
}

char* __wrap_getcwd(char* buffer, size_t size) noexcept {
  char* result = __real_getcwd(buffer, size);
  // glibc getcwd(NULL, size>0) promises a size-byte allocation; retain that
  // capacity, even when the current path needs fewer bytes.
  return buffer ? result : copy_libc_string(result, size);
}

int __wrap_vasprintf(char** output, const char* format, va_list arguments) noexcept {
  char* original = nullptr;
  const int length = __real_vasprintf(&original, format, arguments);
  if (length < 0) { *output = nullptr; return length; }
  const int saved_errno = errno;
  // Copy the counted bytes: printf output may contain embedded NUL characters.
  char* result = static_cast<char*>(std::malloc(static_cast<size_t>(length) + 1));
  if (result) std::memcpy(result, original, static_cast<size_t>(length) + 1);
  sv8_glibc_free(original);
  *output = result;
  errno = result ? saved_errno : ENOMEM;
  return result ? length : -1;
}

char** __wrap_backtrace_symbols(void* const* addresses, int count) noexcept {
  if (count < 0) { errno = EINVAL; return nullptr; }
  char** original = __real_backtrace_symbols(addresses, count);
  if (!original) return nullptr;
  const int saved_errno = errno;
  size_t total = static_cast<size_t>(count) * sizeof(char*);
  for (int i = 0; i < count; ++i) {
    const size_t length = std::strlen(original[i]) + 1;
    if (length > SIZE_MAX - total) {
      sv8_glibc_free(original);
      errno = ENOMEM;
      return nullptr;
    }
    total += length;
  }
  // Like glibc, one allocation owns the pointer table and every string.
  char** result = static_cast<char**>(std::malloc(total ? total : 1));
  if (result) {
    char* cursor = reinterpret_cast<char*>(result + count);
    for (int i = 0; i < count; ++i) {
      const size_t length = std::strlen(original[i]) + 1;
      result[i] = cursor;
      std::memcpy(cursor, original[i], length);
      cursor += length;
    }
  }
  sv8_glibc_free(original);
  errno = result ? saved_errno : ENOMEM;
  return result;
}
}

#if defined(SV8_LINUX_ALLOCATOR_BOUNDARY_TEST)
#include <unistd.h>

static int check_format(char** output, const char* format, ...) {
  va_list arguments;
  va_start(arguments, format);
  int result = __wrap_vasprintf(output, format, arguments);
  va_end(arguments);
  return result;
}

// Built into a separate test-only shared library with the same SDK allocator.
// The production bridge's ten-symbol export map never exposes this entry point.
extern "C" __attribute__((visibility("default")))
int sv8_linux_allocator_boundary_test() {
  for (int i = 0; i < 2000; ++i) {
    errno = EDOM;
    char* text = __wrap_strdup("allocator boundary");
    if (!text || std::strcmp(text, "allocator boundary") || errno != EDOM) return 1;
    std::free(text);
    const char unterminated[] = {'a', 'b'};
    text = __wrap_strndup(unterminated, sizeof(unterminated));
    if (!text || std::strcmp(text, "ab") || errno != EDOM) return 2;
    std::free(text);
    text = __wrap_strndup("ignored", 0);
    if (!text || *text) return 3;
    std::free(text);
    char supplied[PATH_MAX];
    if (__wrap_realpath("/", supplied) != supplied || std::strcmp(supplied, "/")) return 4;
    text = __wrap_realpath("/", nullptr);
    if (!text || std::strcmp(text, "/")) return 5;
    std::free(text);
    errno = 0;
    if (__wrap_realpath("/legado-certainly-missing-directory-718b9f", nullptr) || errno != ENOENT) return 6;
    if (__wrap_getcwd(supplied, sizeof(supplied)) != supplied) return 7;
    text = __wrap_getcwd(nullptr, PATH_MAX);
    if (!text || std::strcmp(text, supplied)) return 8;
    text[PATH_MAX - 1] = '\0';  // Verify explicit-capacity getcwd semantics.
    std::free(text);
    text = __wrap_getcwd(nullptr, 0);
    if (!text || std::strcmp(text, supplied)) return 9;
    std::free(text);
    errno = 0;
    char too_short[1];
    if (__wrap_getcwd(too_short, sizeof(too_short)) || errno != ERANGE) return 10;
    int length = check_format(&text, "x%c%s:%d", 0, "z", 42);
    if (length != 6 || !text || std::memcmp(text, "x\0z:42", 7)) return 11;
    std::free(text);
    void* addresses[] = {reinterpret_cast<void*>(&__wrap_strdup),
                         reinterpret_cast<void*>(&__wrap_backtrace_symbols)};
    char** symbols = __wrap_backtrace_symbols(addresses, 2);
    if (!symbols || !symbols[0] || !symbols[1] || !*symbols[0] || !*symbols[1]) return 12;
    if (reinterpret_cast<char*>(symbols + 2) > symbols[0] || symbols[0] >= symbols[1]) return 13;
    std::free(symbols);
  }
  errno = 0;
  if (__wrap_backtrace_symbols(nullptr, -1) || errno != EINVAL) return 14;
  return 0;
}
#endif
