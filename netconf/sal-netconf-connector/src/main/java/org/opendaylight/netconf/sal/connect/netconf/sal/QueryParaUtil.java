/*
 * Copyright (c) 2019 UTStarcom, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */

package org.opendaylight.netconf.sal.connect.netconf.sal;

import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网元分页指令或总数查询的查询参数工具类
 */
public class QueryParaUtil {

    public static final String CONDITION_REGEX = "(\\w+)(>=|<=|<|=|>){1}(.*)";

    /*
     * 下发网元的表达式要求：
     * 1. 非字符串带单引号
     * 2. 数值型不带单引号
     */
    public static String createCountPara(String moduleName, NetconfPagingService.TableType type, @Nullable String... expressions) {

        String paraValue = String.format("{\"DsName\",{String,\"%s\"}},{\"TblName\",{String,\"%s\"}}", type.toString(), moduleName);
        if (expressions != null && expressions.length > 0) {
            //{"filter",{String,"Almid>100 and Almid<500"}
            StringBuilder targetExp = new StringBuilder();
            String current = quoteNumeric(expressions[0]);
            targetExp.append(current);
            if (expressions.length > 1) {
                for (int i = 1; i < expressions.length; i++) {
                    targetExp.append(" and ");
                    current = quoteNumeric(expressions[i]);
                    targetExp.append(current);
                }
            }
            paraValue = paraValue + String.format("{\"filter\",{String,\"%s\"}", targetExp.toString());
        }

        return "{" + paraValue + "}";
    }

    private static String quoteNumeric(String exp) {
        Pattern pattern = Pattern.compile(CONDITION_REGEX);
        Matcher matcher = pattern.matcher(exp);
        if (matcher.matches()) {
            String key = matcher.group(1);
            String operator = matcher.group(2);
            String value = matcher.group(3);
            if(!StringUtils.isNumeric(value)) {
                value = "'" + value + "'";
            }
            return key + operator + value;
        }
        throw new IllegalArgumentException();
    }


    public static String quoteExp(String expression) {
        Pattern pattern = Pattern.compile(CONDITION_REGEX);
        Matcher matcher = pattern.matcher(expression);
        if (matcher.matches()) {
            String key = matcher.group(1);
            String operator = matcher.group(2);
            String value = matcher.group(3);
            return key + operator + "'" + value + "'";
        }
        throw new IllegalArgumentException();
    }

}
