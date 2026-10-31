// BZip2Helper.h
// Streaming .bz2 decompression on top of the system libbz2 (<bzlib.h>, linked as `bz2` in the podspec).
// Used by the Swift PackManager to unpack sherpa-onnx model packs (.tar.bz2): bz2 -> tar here, tar -> files in Swift.
//
// This header is public in the pod, so Swift in the same module sees it through the generated umbrella header
// (DEFINES_MODULE = YES); no bridging header is needed. Swift name: `IVBZip2.decompressFile(at:to:)`.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

FOUNDATION_EXPORT NSErrorDomain const IVBZip2ErrorDomain;

@interface IVBZip2 : NSObject

/// Decompresses the bzip2 file at `src` into `dst` (overwritten), streaming in 64 KB chunks so memory use stays
/// flat for multi-hundred-megabyte model archives. Concatenated multi-stream files (pbzip2/lbzip2 output) are
/// supported. Returns NO and fills `error` (domain IVBZip2ErrorDomain, code = BZ_* value or 1 for I/O) on failure.
+ (BOOL)decompressFileAtPath:(NSString *)src
                      toPath:(NSString *)dst
                       error:(NSError *_Nullable *_Nullable)error NS_SWIFT_NAME(decompressFile(at:to:));

@end

NS_ASSUME_NONNULL_END
