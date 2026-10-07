package com.scheduler.backend;

import jakarta.persistence.*;

@Entity
@Table(name = "invocations", indexes = {
        @Index(name = "idx_function_id", columnList = "functionId"),
        @Index(name = "idx_function_day", columnList = "functionId, day")
})
public class Invocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "function_id", nullable = false)
    private String functionId;

    @Column(nullable = false)
    private Integer day;

    @Column(name = "minute_of_day", nullable = false)
    private Integer minuteOfDay;

    @Column(name = "invocation_count", nullable = false)
    private Integer invocationCount;

    public Invocation() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFunctionId() { return functionId; }
    public void setFunctionId(String functionId) { this.functionId = functionId; }

    public Integer getDay() { return day; }
    public void setDay(Integer day) { this.day = day; }

    public Integer getMinuteOfDay() { return minuteOfDay; }
    public void setMinuteOfDay(Integer minuteOfDay) { this.minuteOfDay = minuteOfDay; }

    public Integer getInvocationCount() { return invocationCount; }
    public void setInvocationCount(Integer invocationCount) { this.invocationCount = invocationCount; }
}