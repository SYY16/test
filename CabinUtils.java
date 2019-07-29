package com.jike.plane.utils.base;

import com.jike.po.dict.model.CabinModel;
import com.jike.po.flight.model.PlanePriceModel;
import com.jike.redis.helper.LedisHashHelper;
import com.jike.redis.helper.LedisSetHelper;
import com.jike.redis.helper.LedisZSetHelper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.util.StringUtils;

/**
 * @Description: 舱位/舱位等级 字典类
 * @Author: 师岩岩
 * @Date: 2019/6/25 10:16
 */
public class CabinUtils {

    // 按航空公司 分
    private static Map<String, List<CabinModel>> cabinMap = null;
    private static Map<String, Map<String, String>> cabinFullFare = null;

    public static Map<String, PlanePriceModel> refundChangeRules;

    static {
        refundChangeRules = new ConcurrentHashMap<>(742);
    }

    /**
     * 初始化舱位信息
     */
    public static void initDataRedis(LedisHashHelper ledisHashHelper, LedisSetHelper ledisSetHelper, String keys) {
        cabinMap = new ConcurrentHashMap<>();
        cabinFullFare = new ConcurrentHashMap<>();
        Set<String> keySet = ledisSetHelper.members(keys);

        for (String key : keySet) {
            String[] keySplit = key.split(":");
            String airlineCode = keySplit[2];
            if (StringUtils.isEmpty(airlineCode)) {
                continue;
            }
            //退改签规则
            Map<String, String> rulesMap = ledisHashHelper.getAll("FT:RULES:" + airlineCode);
            if (rulesMap != null && rulesMap.size() > 0) {
                PlanePriceModel planePriceModel = null;
                for (Map.Entry<String, String> ruleMap : rulesMap.entrySet()) {
                    String mapKey = airlineCode + "|" + ruleMap.getKey();
                    String value = ruleMap.getValue();
                    if (StringUtils.isEmpty(value)) {
                        continue;
                    }
                    String[] rules = value.split("\\|");
                    if (rules != null && rules.length == 4) {
                        planePriceModel = new PlanePriceModel();
                        planePriceModel.setRefundRules(rules[1]);
                        planePriceModel.setChangeRules(rules[2]);
                        planePriceModel.setRefundChangeRules(rules[3]);
                        refundChangeRules.put(mapKey, planePriceModel);
                    }
                }
            }
            Map<String, String> valueMap = ledisHashHelper.getAll(key);
            if (valueMap != null) {

                List<CabinModel> cabins = new ArrayList<>();

                // 按舱位等级分
                Set<String> cabinClassKeys = valueMap.keySet();
                for (String cabinClass : cabinClassKeys) {
                    String cabinsStr = valueMap.get(cabinClass);

                    CabinModel cm = new CabinModel();
                    cm.setCabinCode(cabinsStr);
                    cm.setCabinCLassCode(cabinClass);

                    cabins.add(cm);
                }
                cabinMap.put(airlineCode, cabins);

                // 按舱位等级获取全价舱位
                Map<String, String> cabinClassFullFare = new HashMap<>();
                cabinClassFullFare.put("F", getFirstCabin("F", valueMap));
                cabinClassFullFare.put("C", getFirstCabin("C", valueMap));
                cabinClassFullFare.put("S", getFirstCabin("S", valueMap));
                cabinClassFullFare.put("Y", getFirstCabin("Y", valueMap));
                cabinFullFare.put(airlineCode, cabinClassFullFare);
            }
        }
    }

    private static String getFirstCabin(String cabinClass, Map<String, String> valueMap) {
        String cabin = valueMap.get(cabinClass);
        if (!StringUtils.isEmpty(cabin)) {
            String[] split = cabin.split(",");
            if (split != null && split.length > 0) {
                String firstCabin = split[0];
                if (!StringUtils.isEmpty(firstCabin) && firstCabin.length() == 1) {
                    return firstCabin;
                }
            }
        }
        return cabinClass;
    }

    /**
     * 获取舱位等级
     *
     * @param airline 航空公司二字码
     * @param cabin 舱位代码 （F/A/C......）
     * @return 舱位等级代码(F / C / Y / S)
     */
    public static String getCabinClass(String airline, String cabin) {
        if (cabinMap != null) {
            List<CabinModel> cabins = cabinMap.get(airline);
            if (cabins != null && cabins.size() > 0) {
                for (CabinModel cm : cabins) {
                    if (cm.getCabinCode().indexOf(cabin) >= 0) {
                        return cm.getCabinCLassCode();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 获得舱位等级的全价仓
     */
    public static String getFullFareCabinByClass(String airline, String cabinClass) {
        Map<String, String> map = cabinFullFare.get(airline);
        if (map != null) {
            return map.get(cabinClass);
        }
        return null;
    }

    /**
     * 获得舱位对应的全价仓
     */
    public static String getFullFareCabinByCabin(String airline, String cabin) {
        String cabinClass = getCabinClass(airline, cabin);
        String cabinFullFare = getFullFareCabinByClass(airline, cabinClass);
        return cabinFullFare;
    }

    public static void main(String[] args) {
         String s =
                "MU|F/P/A/U|F|头等舱;MU|J/C/D/I|C|公务舱;MU|Y/B/M/E/H/K/L/N/R/S/V/T/Z/G/Q/|Y|经济舱;MU|w|S|超级经济舱;CA|P/F/|F|头等舱;CA|C/A/D/Z/J|C|公务舱;CA|Y/B/M/M1/H/H1/K/K1/L/L1/Q/Q1/G/V/V1/|Y|经济舱;CA|W|S|超级经济舱;CZ|F/A/P/J|F|头等舱;CZ|C/D/I|C|公务舱;CZ|Y/B/M/H/U/L/E/V/Z|Y|经济舱;CZ|W/S|S|超级经济舱;ZH|F|F|头等舱;ZH|C/P/A|C|公务舱;ZH|Y/B/M/H/K/L/J/Q/Z/G/V/E/|Y|经济舱;ZH|W|S|超级经济舱;SC|F|F|头等舱;SC|C|C|公务舱;SC|Y/B/M/H/K/L/P/Q/G/V/U/Z/S/E/R/J/T|Y|经济舱;SC|W|S|超级经济舱;3U|F/A|F|头等舱;3U|C/J/I|C|公务舱;3U|Y//T/T1//H/M/G/S/L/Q/E/V/R/K/N/N1/N2|Y|经济舱;MF|P/F/J|F|头等舱;MF|Y/H/B/M/L/K/N/Q/V/T/|Y|经济舱;HU|F/F1/Z/P/A/A1|F|头等舱;HU|C/C1|C|公务舱;HU|Y/B/H/K/L/M/M1/Q/Q1/X/U/E/T/T1/T2/V/N/|Y|经济舱;HO|F/A/|F|头等舱;HO|C/D/|F|公务舱;HO|Y/B/L/M/T/E/H/V/K/W/R/Q/Z/P/X/J/O/N/G/S/I/|Y|经济舱;G5|F/A|F|头等舱;G5|D|C|公务舱;G5|Y/T/H/M/G/S/L/Q/E/V/R/O/U/Z/X/|Y|经济舱;8L|F/F1|F|头等舱;8L|C|C|公务舱;8L|Y/B/H/K/L/M/M1/Q/Q1/X/U/E/D/J|Y|经济舱;BK|F/F1/A/|F|头等舱;BK|W|S|超级经济舱;BK|Y/Y1/B/B1/H/H1/K/K1/M/M1/L/L1/N/N1/Q/Q1/E/E1/U/U1/T/T1/O/O1/I/S/Z/J/R/|Y|经济舱;KN|W|S|超级经济舱;KN|Y/B/M/A/E/H/K/L/N/D/R/Q|Y|经济舱;PN|Y/B/H/K/L/M/R/Q/D/X/U/A/E/W/Z/T/I|Y|经济舱;VD|F|F|头等舱;VD|C|C|公务舱;VD|Y/G/K/H/T/Q/L/S/N/M/E/R|Y|经济舱;EU|F/A/|F|头等舱;EU|C/J/|C|公务舱;EU|Y/T/H/M/G/S/L/Q/E/V/R/K/I|Y|经济舱;GS|Y/B/H/K/L/M/M1/Q/Q1/X/U/E/T/T1/Z/R/N/W/V/J/D/O/S/|Y|经济舱;FM|F/P/|F|头等舱;FM|J|C|公务舱;FM|Y/B/M/E/H/K/L/N/R/S/V/T/Z/H/Q|Y|经济舱;KY|C/F|C|公务舱;KY|H/M/B/Y/Z/Q/J/L/K/W/V/G/|Y|经济舱;XO|F|F|头等舱;XO|Y/B|Y|经济舱;YI|F|F|头等舱;YI|Y/B/H/K/M/L/N/Q/X/E/U/T/O|Y|经济舱;QW|F|F|头等舱;QW|C|C|公务舱;QW|Y/B/M/H/K/L/P/Q/G/V/U/Z/Z1/A/R/R1/E/E1/J/T|Y|经济舱;JD|F/A|F|头等舱;JD|Y/B/H/K/M/L/Q/J/X/U/E/T/Z/D/S/C/G/N/V/W/O|Y|经济舱;GS|F/I/P/A/|F|头等舱;GS|C|C|公务舱;GS|Y/B/H/K/L/M/M1/Q/Q1/X/U/E/T/T1/Z/R/N/W/V/J/D/O/S/G|Y|经济舱;GJ|F|F|头等舱;GJ|C|C|公务舱;GJ|Y/A/O/B/M/H/K/L/P/Q/G/V/U/Z/E/D/I/N/X/J/S/W|Y|经济舱;GJ|R|S|超级经济舱;NS|J|F|头等舱;NS|Y/H/B/M/L/K/N/Q/V/T/R/Z/W/|Y|经济舱;PN|F|F|头等舱;PN|C|C|公务舱;JR|Y/B/M/R/V/T/S/X/W/G/I/O/L/J/Q/E/K/N/U/P/Z/|Y|经济舱;TV|F/A|F|头等舱;TV|Y/B/M/H/K/R/V/G/Q/J/L/|Y|经济舱;UQ|Y/B/H/K/L/M/R/Q/D/X/U/A/E/W/Z/T/I/|Y|经济舱;CN|F/F1/Z/P/A/A1/|F|头等舱;CN|C/C1|C|公务舱;CN|Y/B/H/K/L/M/M1/Q/Q1/X/U/E/T/T1/T2/V/N/|Y|经济舱;DR|F/F1/P/A/|F|头等舱;DR|Y/B/B1/E/H/K/K1/N/R/S/V/T/Z/W/W1/X/X1/Q/M/I/C/U/L/G/O/J|Y|经济舱;DZ|F|F|头等舱;DZ|C|C|公务舱;DZ|Y/B/M/H/K/L/J/Q/Z/G/V/W/|Y|经济舱;FU|F/F1/Z/P/A|F|头等舱;FU|Y/B/H/K/L/M/M1/Q/Q1/X/U/E/T/I|Y|经济舱;GX|F/I/P/A/|F|头等舱;GX|Y/B/H/K/L/M/M1/Q/Q1/X/U/E/T/Z/R/V/J/O/S/G|Y|经济舱";
        initDataRedis(null,null,s);
        System.out.println(getCabinClass("MU", "Z"));
    }

}
