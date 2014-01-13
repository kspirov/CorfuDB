namespace java com.microsoft.corfu


enum CorfuErrorCode {
	OK,
	ERR_OVERWRITE,
	ERR_TRIMMED,
	ERR_UNWRITTEN,
	ERR_BADPARAM,
	ERR_FULL,
	OK_SKIP
}

enum ExtntMarkType {	EX_BEGIN, EX_MIDDLE, EX_SKIP }

struct ExtntInfo {
	1: i64 metaFirstOff,
	2: i32 metaLength,
	3: ExtntMarkType flag=ExtntMarkType.EX_BEGIN
}

struct CorfuHeader {
	1: ExtntInfo extntInf,
	2: bool prefetch,
	3: i64 prefetchOff,
	4: CorfuErrorCode err,
	}
	
typedef binary LogPayload

struct ExtntWrap {
	1: CorfuErrorCode err,
	2: ExtntInfo inf,
	3: list<LogPayload> ctnt,
	}
	

