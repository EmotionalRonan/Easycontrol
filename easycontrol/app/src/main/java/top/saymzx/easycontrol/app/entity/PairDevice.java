package top.saymzx.easycontrol.app.entity;

import top.saymzx.easycontrol.app.helper.PublicTools;

public class PairDevice {

    public String pairCode;

    public String address;

    public PairDevice(
            String pairCode,
            String address
    ){
        this.pairCode = pairCode;
        this.address = address;
    }

    public static PairDevice getDefaultPairDevice(String pairCode, String address) {
        return new PairDevice(pairCode, address);
    }

}

