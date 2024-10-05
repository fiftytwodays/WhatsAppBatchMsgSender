package in.fiftytwodays.crm;

import lombok.Data;

@Data
public class Contact {
    private String serialNo;

    private String name;

    private String prefix;

    private String nickName;

    private String email;

    private String phoneNo;
}