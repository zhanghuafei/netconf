package org.opendaylight.netconf.sal.connect.netconf.sal;

import org.junit.Test;

import static org.junit.Assert.*;

public class QueryParaUtilTest {

    @Test
    public void createCountPara() {
        String s1 = "name=1";
        String s2 = "name=sss";
        String s3 = "vpnId=2342342342";
        String s4 = "vpnId=sdf234";
        System.out.println();
        String expected = "{{\"DsName\",{String,\"cfg\"}},{\"TblName\",{String,\"sss\"}}{\"filter\",{String,\"name=1 and name='sss' and vpnId=2342342342 and vpnId='sdf234'\"}}";
        assertTrue(expected.equals(QueryParaUtil.createCountPara("sss", NetconfPagingService.TableType.cfg, s1, s2, s3, s4)));
    }
}