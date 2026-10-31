// BZip2Helper.m
// See BZip2Helper.h. The decompression loop is plain C (BZ2_bzReadOpen / BZ2_bzRead / BZ2_bzReadGetUnused) so it
// can be unit-tested outside Xcode; the Objective-C method only maps paths and errors.

#import "BZip2Helper.h"
#include <bzlib.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

NSErrorDomain const IVBZip2ErrorDomain = @"dev.indicvoice.bzip2";

/* Streams a (possibly multi-stream) bzip2 file from `in` to `out` in 64 KB chunks.
   Returns 0 on success, otherwise a BZ_* error code (negative) or 1 for I/O errors; `msg` gets a description. */
static int iv_bz2_decompress(FILE *in, FILE *out, char *msg, size_t msgLen) {
  const int bufLen = 65536;
  char *buf = (char *)malloc((size_t)bufLen);
  if (!buf) { snprintf(msg, msgLen, "out of memory"); return 1; }
  int rc = 0;
  int streams = 0;
  char *carry = NULL;  /* bytes libbz2 read past the end of the previous stream */
  int nCarry = 0;
  for (;;) {
    int bzerr = BZ_OK;
    BZFILE *bz = BZ2_bzReadOpen(&bzerr, in, 0, 0, carry, nCarry);
    free(carry); carry = NULL; nCarry = 0;  /* BZ2_bzReadOpen copies the unused bytes */
    if (!bz || bzerr != BZ_OK) {
      if (streams == 0) { snprintf(msg, msgLen, "BZ2_bzReadOpen failed (%d)", bzerr); rc = bzerr != 0 ? bzerr : 1; }
      if (bz) { int e; BZ2_bzReadClose(&e, bz); }
      break;
    }
    int streamDone = 0;
    int failed = 0;
    while (!streamDone) {
      int n = BZ2_bzRead(&bzerr, bz, buf, bufLen);
      if (bzerr == BZ_DATA_ERROR_MAGIC && streams > 0) {
        /* Trailing bytes after the last stream (padding): tolerated. */
        failed = 2;
        break;
      }
      if (bzerr != BZ_OK && bzerr != BZ_STREAM_END) {
        snprintf(msg, msgLen, "BZ2_bzRead failed (%d)", bzerr);
        rc = bzerr;
        failed = 1;
        break;
      }
      if (n > 0 && fwrite(buf, 1, (size_t)n, out) != (size_t)n) {
        snprintf(msg, msgLen, "write failed");
        rc = 1;
        failed = 1;
        break;
      }
      if (bzerr == BZ_STREAM_END) streamDone = 1;
    }
    if (failed) { int e; BZ2_bzReadClose(&e, bz); break; }
    streams++;
    /* End of one bzip2 stream. Files written by pbzip2 / lbzip2 concatenate several streams, so keep whatever
       libbz2 already read past this stream and continue with it. */
    void *unused = NULL;
    int nUnused = 0;
    int e2 = BZ_OK;
    BZ2_bzReadGetUnused(&e2, bz, &unused, &nUnused);
    if (e2 == BZ_OK && nUnused > 0 && unused != NULL) {
      carry = (char *)malloc((size_t)nUnused);
      if (carry) { memcpy(carry, unused, (size_t)nUnused); nCarry = nUnused; }
    }
    int e3 = BZ_OK;
    BZ2_bzReadClose(&e3, bz);
    if (nCarry == 0) {
      int c = fgetc(in);           /* one byte of pushback is always allowed */
      if (c == EOF) break;
      ungetc(c, in);
    }
  }
  free(carry);
  free(buf);
  if (rc == 0 && streams == 0) { snprintf(msg, msgLen, "no bzip2 stream found"); rc = 1; }
  return rc;
}

@implementation IVBZip2

+ (BOOL)decompressFileAtPath:(NSString *)src toPath:(NSString *)dst error:(NSError **)error {
  FILE *in = fopen([src fileSystemRepresentation], "rb");
  if (!in) {
    if (error) {
      *error = [NSError errorWithDomain:IVBZip2ErrorDomain code:1
                               userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithFormat:@"cannot open %@", src]}];
    }
    return NO;
  }
  FILE *out = fopen([dst fileSystemRepresentation], "wb");
  if (!out) {
    fclose(in);
    if (error) {
      *error = [NSError errorWithDomain:IVBZip2ErrorDomain code:1
                               userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithFormat:@"cannot create %@", dst]}];
    }
    return NO;
  }
  char msg[256];
  msg[0] = 0;
  int rc = iv_bz2_decompress(in, out, msg, sizeof(msg));
  fclose(in);
  int closeRc = fclose(out);
  if (rc == 0 && closeRc != 0) { rc = 1; snprintf(msg, sizeof(msg), "flush failed"); }
  if (rc != 0) {
    [[NSFileManager defaultManager] removeItemAtPath:dst error:nil];
    if (error) {
      *error = [NSError errorWithDomain:IVBZip2ErrorDomain code:rc
                               userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithFormat:@"bzip2: %s", msg]}];
    }
    return NO;
  }
  return YES;
}

@end
