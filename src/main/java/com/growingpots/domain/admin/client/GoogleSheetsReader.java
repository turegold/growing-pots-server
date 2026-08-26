package com.growingpots.domain.admin.client;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.SheetsScopes;
import com.google.api.services.sheets.v4.model.ValueRange;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.growingpots.domain.admin.dto.sheet.CourseRow;
import com.growingpots.domain.admin.dto.sheet.DepartmentRow;
import com.growingpots.domain.admin.dto.sheet.DivisionRow;
import com.growingpots.domain.admin.dto.sheet.RequirementCourseRow;
import com.growingpots.domain.admin.dto.sheet.SchoolRow;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 서비스 계정으로 시드 데이터 스프레드시트를 직접 읽어온다. 시트 컬럼 순서를 해석하는 책임은
// 여기 한 곳에만 있다 - Python 쪽 sheets_writer가 쓰는 헤더 순서와 반드시 맞춰야 한다.
@Component
public class GoogleSheetsReader {

    private final String credentialsPath;
    private final String spreadsheetId;

    // credentials 파일을 생성자에서 바로 읽으면, seed-data.google-credentials-path가 설정 안 된
    // 환경(테스트, 이 기능을 안 쓰는 로컬 실행 등)에서 이 빈 하나 때문에 애플리케이션 전체가 기동
    // 실패한다. 실제로 시트를 읽을 때(동기화 엔드포인트 호출 시)까지 생성을 미룬다.
    private Sheets sheetsService;

    public GoogleSheetsReader(
            @Value("${seed-data.google-credentials-path}") String credentialsPath,
            @Value("${seed-data.spreadsheet-id}") String spreadsheetId
    ) {
        this.credentialsPath = credentialsPath;
        this.spreadsheetId = spreadsheetId;
    }

    private synchronized Sheets sheetsService() {
        if (sheetsService == null) {
            try {
                GoogleCredentials credentials = GoogleCredentials
                        .fromStream(new FileInputStream(credentialsPath))
                        .createScoped(List.of(SheetsScopes.SPREADSHEETS_READONLY));
                sheetsService = new Sheets.Builder(
                        GoogleNetHttpTransport.newTrustedTransport(),
                        GsonFactory.getDefaultInstance(),
                        new HttpCredentialsAdapter(credentials)
                ).setApplicationName("growing-pots-seed-sync").build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        return sheetsService;
    }

    public List<SchoolRow> readSchools() throws IOException {
        return rawRows("School").stream()
                .map(r -> new SchoolRow(cell(r, 0)))
                .toList();
    }

    public List<DepartmentRow> readDepartments() throws IOException {
        return rawRows("Department").stream()
                .map(r -> new DepartmentRow(cell(r, 0), cell(r, 1), cell(r, 2)))
                .toList();
    }

    public List<DivisionRow> readDivisions() throws IOException {
        return rawRows("Division").stream()
                .map(r -> new DivisionRow(cell(r, 0), cell(r, 1), cell(r, 2)))
                .toList();
    }

    public List<CourseRow> readCourses() throws IOException {
        return rawRows("Course").stream()
                .map(r -> new CourseRow(
                        cell(r, 0), cell(r, 1), cell(r, 2), cell(r, 3), cell(r, 4),
                        cell(r, 5), cell(r, 6), cell(r, 7), cell(r, 8), cell(r, 9),
                        cell(r, 10), cell(r, 11), cell(r, 12)
                ))
                .toList();
    }

    public List<RequirementCourseRow> readRequirementCourses() throws IOException {
        return rawRows("RequirementCourse").stream()
                .map(r -> new RequirementCourseRow(cell(r, 0), cell(r, 1), cell(r, 2), cell(r, 3), cell(r, 4), cell(r, 5)))
                .toList();
    }

    private List<List<Object>> rawRows(String sheetName) throws IOException {
        ValueRange response = sheetsService().spreadsheets().values()
                .get(spreadsheetId, sheetName)
                .execute();
        List<List<Object>> values = response.getValues();
        if (values == null || values.size() <= 1) {
            return List.of();
        }
        return values.subList(1, values.size()); // 헤더 행 제외
    }

    // 시트 API는 행 끝의 빈 셀들을 아예 생략해서 돌려주므로, 인덱스가 범위를 벗어나면 빈 문자열로 취급한다.
    private static String cell(List<Object> row, int index) {
        return index < row.size() && row.get(index) != null ? String.valueOf(row.get(index)).trim() : "";
    }
}
