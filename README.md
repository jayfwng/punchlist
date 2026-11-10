# 펀치리스트 (PunchList) — 건물 하자 점검 앱

현장에서 하자를 **공종 → 층 → 장소 → 현황 및 문제점 → 사진** 순서로 기록하고,
기존 `PUNCH LIST` 엑셀 양식(집계표 + 공종별 시트, 현장사진 1·2 삽입)과 사진 파일로 내보내는 안드로이드 앱입니다.

## 설치
`dist/PunchList-v1.0.0.apk` 를 휴대폰으로 옮겨 실행 → "출처를 알 수 없는 앱 설치 허용" 후 설치. (Android 7.0 이상)

## 사용 흐름
1. **새 현장 등록** — 건물명, 점검일자, 점검자, 지하/지상/옥탑 층수, 승강기 호기 수, 옥상·전층·외부, 직접 추가 층(PIT 등). 상단 ✎ 로 언제든 수정.
2. **공종 선택** — 건축 / 기계 / 전기 / 소방 / 승강기 / 안전(공통)
3. **＋ 하자 등록** → 층 버튼 선택 → 장소(최근 입력값 버튼) · 설비명(선택) · 현황 및 문제점(자주 쓰는 문구 버튼) → 📷 촬영 / 🖼 앨범
4. **저장 후 다음** — 같은 층·장소로 바로 다음 하자 입력
5. 조치 정보(업체·조치내역·조치일자·완료)는 접힌 칸에서 선택 입력
6. **내보내기** — 엑셀(.xlsx) + 사진 (워터마크 있음 / 없음 / 둘 다 / 안 함), 휴대폰 저장 또는 공유(카톡·메일, ZIP)
   - 엑셀: `다운로드/PunchList/건물명_Punch List_날짜.xlsx`
   - 사진: `사진(Pictures)/PunchList/건물명_날짜/공종/001_층_장소.jpg`

## 사진 품질
- 앱 보관/사진 저장: 긴 변 1920px, JPEG 88 (원본보다 가볍고 충분히 선명)
- 엑셀 삽입: 긴 변 1024px (기존 양식 첨부 사진 ≈250px 대비 약 4배 해상도) — 셀 크기는 기존 양식과 동일
- 워터마크: 하단 반투명 띠에 `건물 | 층 | 위치`, `[공종] 설비 - 문제점`, `No. / 촬영일시`

## 빌드
```bash
sudo apt-get install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
./build.sh
```
Gradle 없이 SDK 도구로 직접 빌드합니다. 서명 키(`keystore/punchlist.jks`)는 저장소에 포함하지 않습니다.
업데이트 APK를 기존 앱 위에 설치하려면 같은 키로 서명해야 하므로 키 파일을 따로 보관하세요
(`KEYSTORE`, `KEYSTORE_PASS` 환경변수로 지정 가능).

## 구조
- `app/src/.../MainActivity.java` — WebView 호스트, 카메라/앨범, JS 브리지, 데이터 저장
- `app/src/.../PhotoUtil.java` — 축소·회전·워터마크
- `app/src/.../XlsxWriter.java` — 양식 엑셀 직접 생성 (안드로이드 비의존, `tools/XlsxTest.java` 로 PC에서 테스트)
- `app/src/.../Exporter.java` — 기기 저장(MediaStore) / 공유 ZIP
- `app/assets/www/` — 화면(UI)
