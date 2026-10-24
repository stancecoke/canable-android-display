package com.androidcan;

public class BitTiming {
    public final int propSeg;
    public final int phaseSeg1;
    public final int phaseSeg2;
    public final int sjw;
    public final int brp;

    public BitTiming(int propSeg, int phaseSeg1, int phaseSeg2, int sjw, int brp) {
        this.propSeg = propSeg;
        this.phaseSeg1 = phaseSeg1;
        this.phaseSeg2 = phaseSeg2;
        this.sjw = sjw;
        this.brp = brp;
    }
}
