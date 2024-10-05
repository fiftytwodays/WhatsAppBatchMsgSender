package in.fiftytwodays.crm;

import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
public class ExcelReader {

    private static Row.MissingCellPolicy MISSING_CELL_POLICY = Row.MissingCellPolicy.CREATE_NULL_AS_BLANK;

    public List<Contact> readExcel(String filePath) throws IOException {

        List<Contact> contacts = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new File(filePath))) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rowIterator = sheet.iterator();

            // Skip header row
            if (rowIterator.hasNext()) {
                rowIterator.next();
            }

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                Iterator<Cell> cellIterator = row.cellIterator();

                Contact contact = new Contact();
                int i = 0;
                contact.setSerialNo(row.getCell(i++, MISSING_CELL_POLICY).toString());
                contact.setName(row.getCell(i++, MISSING_CELL_POLICY).toString());
                contact.setPrefix(row.getCell(i++, MISSING_CELL_POLICY).toString());
                contact.setNickName(row.getCell(i++, MISSING_CELL_POLICY).toString());
                contact.setEmail(row.getCell(i++, MISSING_CELL_POLICY).toString());
                contact.setPhoneNo(row.getCell(i++, MISSING_CELL_POLICY).toString());
                contacts.add(contact);
            }
        }
        return contacts;
    }
}
