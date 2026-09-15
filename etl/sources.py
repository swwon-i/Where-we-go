"""적재 대상 정의.

인허가 CSV는 두 파일이지만 컬럼 구조가 같다. `source` 코드로만 구분해 한 파이프라인으로 처리한다.
관리번호는 파일 간에 겹칠 수 있으므로 `UNIQUE(source, source_id)` 로 묶는다.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

#: 인허가 CSV 39컬럼. poi_raw 컬럼 순서와 1:1로 대응한다.
RAW_COLUMNS: list[str] = [
    "open_local_gov_code",
    "mgmt_no",
    "license_date",
    "biz_status_name",
    "closed_date",
    "site_area",
    "site_postal_code",
    "road_postal_code",
    "biz_name",
    "biz_type_name",
    "data_update_type",
    "building_ownership",
    "factory_office_staff",
    "factory_prod_staff",
    "factory_sales_staff",
    "water_facility_type",
    "male_staff",
    "multi_use_yn",
    "data_updated_at",
    "road_address",
    "grade_type",
    "deposit",
    "hq_staff",
    "detail_status_name",
    "detail_status_code",
    "total_facility_scale",
    "female_staff",
    "biz_status_code",
    "surroundings_type",
    "monthly_rent",
    "hygiene_biz_type",
    "traditional_main_food",
    "traditional_desig_no",
    "phone",
    "coord_x",
    "coord_y",
    "jibun_address",
    "homepage",
    "last_modified_at",
]

#: 원본 헤더(한글) → poi_raw 컬럼명. CSV 헤더 검증에 쓴다.
KOREAN_HEADER: list[str] = [
    "개방자치단체코드", "관리번호", "인허가일자", "영업상태명", "폐업일자",
    "소재지면적", "소재지우편번호", "도로명우편번호", "사업장명", "업태구분명",
    "데이터갱신구분", "건물소유구분명", "공장사무직직원수", "공장생산직직원수",
    "공장판매직직원수", "급수시설구분명", "남성종사자수", "다중이용업소여부",
    "데이터갱신시점", "도로명주소", "등급구분명", "보증액", "본사직원수",
    "상세영업상태명", "상세영업상태코드", "시설총규모", "여성종사자수",
    "영업상태코드", "영업장주변구분명", "월세액", "위생업태명",
    "전통업소주된음식", "전통업소지정번호", "전화번호", "좌표정보(X)",
    "좌표정보(Y)", "지번주소", "홈페이지", "최종수정시점",
]

#: 원본 좌표계. D0 검증으로 확정했다 (계획서 §6).
SOURCE_EPSG = 5174
#: 저장 좌표계. 미터 단위라 반경 검색·스냅 거리를 그대로 쓴다.
TARGET_EPSG = 5186

#: 영업 중으로 볼 영업상태명. 그 외는 CLOSED.
ACTIVE_STATUS_NAMES = {"영업/정상", "영업"}


@dataclass(frozen=True)
class Source:
    code: str
    label: str
    path: Path
    #: 모든 인허가 CSV 는 CP949 다. (시각표만 UTF-8 BOM — 계획서 §3)
    encoding: str = "cp949"


SOURCES: dict[str, Source] = {
    "food": Source(
        code="LOCALDATA_FOOD",
        label="일반음식점",
        path=Path("csv/장소/식품_일반음식점_서울특별시.csv"),
    ),
    "rest": Source(
        code="LOCALDATA_REST",
        label="휴게음식점",
        path=Path("csv/장소/식품_휴게음식점_서울특별시.csv"),
    ),
}
