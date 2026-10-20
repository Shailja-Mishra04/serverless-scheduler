package com.scheduler.backend;

import jakarta.persistence.*;

@Entity
@Table(name = "lstm_predictions", indexes = {
        @Index(name = "idx_pred_function", columnList = "functionId")
})
public class LstmPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "function_id", nullable = false)
    private String functionId;

    @Column(name = "call_minute", nullable = false)
    private Long callMinute;      // minute of the call the prediction was made at

    @Column(name = "pred_gap", nullable = false)
    private Double predGap;       // predicted minutes until the next call

    public LstmPrediction() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFunctionId() { return functionId; }
    public void setFunctionId(String functionId) { this.functionId = functionId; }

    public Long getCallMinute() { return callMinute; }
    public void setCallMinute(Long callMinute) { this.callMinute = callMinute; }

    public Double getPredGap() { return predGap; }
    public void setPredGap(Double predGap) { this.predGap = predGap; }
}