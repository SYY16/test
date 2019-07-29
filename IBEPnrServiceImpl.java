package com.jike.plane.service.assist.ibe;

import com.jike.base.consts.DictConsts;
import com.jike.plane.service.itf.assist.ibe.IBEPnrService;
import com.jike.plane.utils.base.CityAirportUtils;
import com.jike.po.flight.model.PlaneODModel;
import com.jike.po.flight.model.PlaneOrderModel;
import com.jike.po.flight.model.PlaneTripModel;
import com.jike.po.flight.model.TicketPassengerModel;
import com.jike.utils.base.BeanUtils;
import com.jike.utils.base.DateHelper;
import com.jike.utils.base.StringUtils;
import com.jike.utils.consts.SystemConst;
import com.jike.utils.web.CommonException;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.httpclient.DefaultHttpMethodRetryHandler;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpState;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.params.HttpMethodParams;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

/**
 * @Author: 师岩岩
 * @Date: 2019/7/10 10:02
 */
@Slf4j
@Service
public class IBEPnrServiceImpl extends AbstractIBEService implements IBEPnrService {

    /**
     * IBE+ 相关配置
     */

    private static String[] regexs = new String[4];

    static {
        //身份证正则
        regexs[0] = "[NI](([0-9]{18})|([0-9]{17}(x|X{1}))|([0-9]{5,18}))/P[0-9]{1}";
        //护照正则
        regexs[1] = "[NI]([a-zA-Z0-9]){5,17}/P[0-9]{1}";
        //港澳通行证
        regexs[2] = "[NI][HMhm]{1}([0-9]{10})|([0-9]{8})/P[0-9]{1}";
        //台胞通行证
        regexs[3] = "[NI]([0-9]{8})|([0-9]{10})/P[0-9]{1}";
    }


    public static void main(String[] args) {
        IBEPnrServiceImpl i = new IBEPnrServiceImpl();
        i.resRetPNR("KQVKM6");//国内
//        String kyzmj2 = i.resRetPNR("JP1KNG");//国际

    }

    /**
     * AirResRet
     */
    @Override
    public PlaneOrderModel resRetPNR(String pnr) {
        // 创建 identity XML报文
        Document document = DocumentHelper.createDocument();
        Element root = document.addElement("OTA_AirResRetRQ");
        root.attributeValue("RetCreateTimeInd", "true");
        root.attributeValue("SearchCodeShareInd", "true");
        root.attributeValue("SynTicketNumberInd", "true");
        //设置属性
        root.addElement("Header").setText("*GAPP");
        Element pos = root.addElement("POS");
        pos.addElement("Source")
                .addAttribute("PseudoCityCode", SystemConst.IBE_PLUS_SZJK_OFFICE_ID);
        root.addElement("BookingReferenceID").addAttribute("ID", pnr);
        //转为XML字符串
        String reqXml = document.asXML();
        log.info("[===>请求IBE解析PNR接口 ,request：{}]", reqXml);
        //发送请求
        String result = null;
        try {
            result = send(SystemConst.IBE_PLUS_SZJK_URL + "AirResRet", reqXml, null);
            log.info("[===>请求IBE解析PNR接口 ,response：{}]", result);
        } catch (IOException e) {
            log.error("[===> 请求IBE解析PNR接口报错  error]", e);
            throw new CommonException("请求IBE解析PNR接口出错",
                    "100000");
        }
        //解析返回PNR信息
        PlaneOrderModel planeOrderModel = analysisPnrXml(result);
        System.out.println(planeOrderModel);
        return planeOrderModel;
    }


    /**
     * 解析PNR XML字符串
     */
    public PlaneOrderModel analysisPnrXml(String pnrXml) {
        PlaneOrderModel planeOrderModel = new PlaneOrderModel();
        Element rootElement = null;
        if (pnrXml != null) {
            try {
                rootElement = StringUtils.analyzeRootElement(pnrXml);
            } catch (DocumentException e) {
                log.error("[===> 转换IBE解析PNR返回结果出错  error]", e);
                throw new CommonException("请求IBE解析PNR接口出错",
                        "100000");
            }
        }
        //解析错误
        Element errorsEle = rootElement.element("Errors");
        if (errorsEle != null) {
            List<Element> errors = errorsEle.elements("Error");
            if (!CollectionUtils.isEmpty(errors)) {
                Element errorElement = errors.get(0);
                String code = errorElement.attributeValue("Code");
                String shortText = errorElement.attributeValue("ShortText");
                throw new CommonException(
                        String.format("【国内PNR解析失败,errorCode:%s,msg:%s】", code, shortText));
            }
        }
        //根元素
        Element airResRet = rootElement.element("AirResRet");
        //航段信息
        Element flightSegments = airResRet.element("FlightSegments");
        List<Element> flightSegmentList = flightSegments.elements("FlightSegment");
        //解析行程信息
        List<PlaneODModel> planeODModels = analysisSegment(flightSegmentList);
        planeOrderModel.setOds(planeODModels);
        //解析旅客信息
        List<TicketPassengerModel> ticketPassengerModels = analysisPassenger(airResRet);
        planeOrderModel.setPassengers(ticketPassengerModels);
        //解析票号
        String ticketNo = airResRet.element("TicketItemInfo").attributeValue("TicketNumber");
        planeOrderModel.setPlaneTicketNo(ticketNo);
        String pnr = airResRet.element("BookingReferenceID").attributeValue("ID");
        planeOrderModel.setPnr(pnr);
        //是否出票
        String IsIssued = airResRet.element("Ticketing").attributeValue("IsIssued");

        System.out.println("== " + rootElement.element("AirResRet").getStringValue());

        return planeOrderModel;
    }

    /**
     * 封装航段信息
     */
    public List<PlaneODModel> analysisSegment(List<Element> flightSegmentList) {
        List<PlaneODModel> planeODModels = new ArrayList<>();
        //封装航段信息 planeOdModel、planeTripModel
        if (flightSegmentList != null && flightSegmentList.size() > 0) {
            flightSegmentList.forEach(flightSegment -> {
                PlaneODModel planeODModel = new PlaneODModel();
                PlaneTripModel planeTripModel = new PlaneTripModel();
                planeTripModel.setAirlineCode(
                        flightSegment.element("MarketingAirline").attributeValue("Code"));
                //2019-07-07T10:55:00
                planeTripModel.setDepartTime(DateHelper.parseDateTime(
                        flightSegment.attributeValue("DepartureDateTime").replaceFirst("T", " ")));
                planeTripModel.setTakeOffTime(DateHelper.parseDateTime(
                        flightSegment.attributeValue("DepartureDateTime").replaceFirst("T", " ")));
                planeTripModel.setArriveTime(DateHelper.parseDateTime(
                        flightSegment.attributeValue("ArrivalDateTime").replaceFirst("T", " ")));
                //航班号
                planeTripModel.setFlightNo(flightSegment.attributeValue("FlightNumber"));
                planeTripModel
                        .setIsShare(Boolean.valueOf(flightSegment.attributeValue("CodeshareInd")));
                //出发机场信息 --- <DepartureAirport LocationCode="XMN" Terminal="T4"/>
                planeTripModel.setDepartAirportCode(
                        flightSegment.element("DepartureAirport").attributeValue("LocationCode"));
                planeTripModel.setDepartCityCode(CityAirportUtils
                        .getCityCodeByAirportCode(planeTripModel.getDepartAirportCode()));
                planeTripModel.setDepartCityName(
                        CityAirportUtils.getCityNameByCode(planeTripModel.getDepartCityCode()));
                planeTripModel.setDepartAirportName(CityAirportUtils
                        .getAirportNameByCode(planeTripModel.getDepartAirportCode()));
                planeTripModel.setDepartAirportTower(
                        flightSegment.element("DepartureAirport").attributeValue("Terminal"));
                //到达机场信息 --- <ArrivalAirport LocationCode="SHA" Terminal="T2"/>
                planeTripModel.setArriveAirportCode(
                        flightSegment.element("ArrivalAirport").attributeValue("LocationCode"));
                planeTripModel.setArriveCityCode(CityAirportUtils
                        .getCityCodeByAirportCode(planeTripModel.getDepartAirportCode()));
                planeTripModel.setArriveCityName(
                        CityAirportUtils.getCityNameByCode(planeTripModel.getDepartCityCode()));
                planeTripModel.setArriveAirportName(CityAirportUtils
                        .getAirportNameByCode(planeTripModel.getDepartAirportCode()));
                planeTripModel.setArriveAirportTower(
                        flightSegment.element("ArrivalAirport").attributeValue("Terminal"));
                //舱位  <BookingClassAvail ResBookDesigCode="E"/>
                planeTripModel.setCabin(flightSegment.element("BookingClassAvail")
                        .attributeValue("ResBookDesigCode"));
                BeanUtils.copyWithoutNullProperties(planeTripModel, planeODModel);
                planeODModel.setSegments(Arrays.asList(planeTripModel));
                planeODModels.add(planeODModel);
            });
        }
        return planeODModels;
    }

    /**
     * 封装旅客信息
     */
    public List<TicketPassengerModel> analysisPassenger(Element airResRet) {
        List<Element> specialServiceRequest = airResRet.elements("SpecialServiceRequest");
        List<Element> airTravelers = airResRet.elements("AirTraveler");
        List<TicketPassengerModel> ticketPassengerModels = new ArrayList<>();
        //获取包含用户信息字符串
        Map<String, String> passengerMap = new HashMap<>(2);
        if (specialServiceRequest != null && specialServiceRequest.size() > 0) {
            Iterator<Element> iterator = specialServiceRequest.iterator();
            while (iterator.hasNext()) {
                System.out.println(iterator);
                Element iteratorNext = iterator.next();
                //国内：FOID 、国际：DOCS
                if (!iteratorNext.attributeValue("SSRCode").equals("FOID")) {
                    iterator.remove();
                } else {
                    String rphNum = iteratorNext.element("TravelerRefNumber").attributeValue("RPH");
                    String passengerText = iteratorNext.elementText("Text");
                    passengerMap.put(rphNum, passengerText);
                }
            }
        }
        //解析旅客基本信息
        if (!CollectionUtils.isEmpty(airTravelers)) {
            airTravelers.forEach(airTraveler -> {
                String rph = airTraveler.attributeValue("RPH");
                String psgText = passengerMap.get("P" + rph);
                System.out.println("---" + psgText);
                TicketPassengerModel ticketPassengerModel = new TicketPassengerModel();
                //乘客类型
                ticketPassengerModel.setPassengerType(
                        airTraveler.element("PassengerTypeQuantity").attributeValue("Code"));
                //乘客姓名
                String surname = airTraveler.element("PersonName").elementText("Surname");
                ticketPassengerModel.setIdcName(surname);
                if (!StringUtils.isEmpty(psgText)) {
                    //判断购票使用证件类型
                    Pattern pattern = null;
                    Matcher matcher = null;
                    for (int x = 0; x < regexs.length; x++) {
                        pattern = Pattern.compile(regexs[x], Pattern.MULTILINE);
                        matcher = pattern.matcher(psgText);
                        if (matcher.find()) {
                            //解析证件号
                            int index = psgText.indexOf("NI");
                            int index1 = psgText.indexOf("/");
                            if (index > -1 && index1 > -1) {
                                ticketPassengerModel.setIdcNo(psgText.substring(index + 2, index1));
                            }
                            String idcNo = ticketPassengerModel.getIdcNo();
                            System.out.println("idcNo = " + idcNo);
                            switch (x) {
                                case 0:
                                    //1.身份证购票  乘客证件号（身份证号）  FOID FM HK1 NI510212197701310318/P1
                                    ticketPassengerModel
                                            .setIdcType(DictConsts.IDC_TYPE_IDENTITYCARD);
                                    ticketPassengerModel
                                            .setNationality(DictConsts.NATIONALITY_CHINA_TWO);
                                    //根据身份证号解析生日
                                    if (!StringUtils.isEmpty(idcNo)) {
                                        String dateStr = idcNo.substring(6, 14);
                                        ticketPassengerModel.setBirthday(
                                                DateHelper.parseDateOnlyYYYYMMDD(dateStr));
                                    }
                                    //根据身份证号解析性别
                                    String sCardNum = idcNo.substring(16, 17);
                                    if (Integer.parseInt(sCardNum) % 2 != 0) {
                                        ticketPassengerModel.setSex(DictConsts.SEX_MALE);
                                    } else {
                                        ticketPassengerModel.setSex(DictConsts.SEX_FEMALE);
                                    }
                                    break;
                                case 1:
                                    //护照解析 ---FOID MU HK1 NIEA1164452/P1
                                    ticketPassengerModel.setIdcType(DictConsts.IDC_TYPE_PASSPORT);
                                    break;
                                case 2:
                                    ticketPassengerModel
                                            .setIdcType(DictConsts.IDC_TYPE_GANGAOTONGXING);
                                    break;
                                case 3:
                                    ticketPassengerModel
                                            .setIdcType(DictConsts.IDC_TYPE_TAIWANTONGXING);
                                    break;
                                default:
                                    ticketPassengerModel.setIdcType(DictConsts.IDC_TYPE_OTHER);
                                    break;
                            }
                        }
                    }
                }
                //解析票面信息
                Element priceInfo = airResRet.element("FN");
                analysisPrice(priceInfo, ticketPassengerModel);
                ticketPassengerModels.add(ticketPassengerModel);
            });
        }
        return ticketPassengerModels;
    }

    /**
     * 解析乘客信息
     */

    /**
     * 封装票面信息（运价信息）
     */
    public void analysisPrice(Element priceInfo, TicketPassengerModel ticketPassengerModel) {
        final BigDecimal[] facePrice = {null};
        final BigDecimal[] floorPrice = {null};
        final BigDecimal[] taxAmount = {null};
        Boolean isCompleted =
                (facePrice[0] == null) || (floorPrice[0] == null) || (taxAmount[0] == null);
        if (priceInfo != null) {
            List<Element> fnItems = priceInfo.elements("FNItem");
            if (fnItems != null && fnItems.size() > 0) {
                fnItems.forEach(fnItem -> {
                    if (fnItem.attributeValue("Code").equals("FCNY")) {
                        facePrice[0] = new BigDecimal(fnItem.attributeValue("Amount"));
                    } else if (fnItem.attributeValue("Code").equals("ACNY")) {
                        floorPrice[0] = new BigDecimal(fnItem.attributeValue("Amount"));
                    } else if (fnItem.attributeValue("Code").equals("XCNY")) {
                        taxAmount[0] = new BigDecimal(fnItem.attributeValue("Amount"));
                    }
                });
            }
        }
        ticketPassengerModel.setFacePrice(facePrice[0]);
        ticketPassengerModel.setFloorPrice(floorPrice[0]);
        ticketPassengerModel.setTax(taxAmount[0]);
    }


}
