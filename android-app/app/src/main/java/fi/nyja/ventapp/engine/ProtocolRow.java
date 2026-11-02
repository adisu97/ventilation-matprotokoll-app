package fi.nyja.ventapp.engine;

public final class ProtocolRow {
    public final String room;
    public final String exhaustName;
    public final String exhaustSize;
    public final Double exhaustBehov;
    public final String supplyName;
    public final String supplySize;
    public final Double supplyBehov;
    public final boolean unsure;
    public final String unsureReason;
    public final double confidence;

    public ProtocolRow(String room, String exhaustName, String exhaustSize, Double exhaustBehov,
                       String supplyName, String supplySize, Double supplyBehov,
                       boolean unsure, String unsureReason, double confidence) {
        this.room = room == null ? "" : room;
        this.exhaustName = exhaustName == null ? "" : exhaustName;
        this.exhaustSize = exhaustSize;
        this.exhaustBehov = exhaustBehov;
        this.supplyName = supplyName == null ? "" : supplyName;
        this.supplySize = supplySize;
        this.supplyBehov = supplyBehov;
        this.unsure = unsure;
        this.unsureReason = unsureReason == null ? "" : unsureReason;
        this.confidence = confidence;
    }
}
