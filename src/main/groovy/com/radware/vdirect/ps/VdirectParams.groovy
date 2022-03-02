package com.radware.vdirect.ps

import com.radware.alteon.workflow.impl.java.Param

class VdirectParams {
    @Param (prompt = "LLS Host Address",type = 'ip', required = true)
    public String vdirectIp
    @Param(prompt = "LLS User Name", type = 'string', required = true)
    public String userName
    @Param(prompt = "LLS User Password", type = 'string', format='password', required = true)
    public String userPassword
}
