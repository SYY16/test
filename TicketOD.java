package com.jike.po.flight.bean;

import java.io.Serializable;
import lombok.Data;

/**
 * @Description:
 * @Author: 师岩岩
 * @Date: 2019/6/25 14:47
 */
@Data
public class TicketOD implements Serializable {

    private static final long serialVersionUID = -1646420231343761069L;

    private Long planeTicketId;

    private Long planeOdId;

}
