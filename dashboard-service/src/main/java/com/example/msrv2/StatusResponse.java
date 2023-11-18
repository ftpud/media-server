package com.example.msrv2;

import java.util.List;

class StatusResponse {
    private String serviceName;
    private String status;
    private Boolean deployable;

    private List<String> logs;

    // getters and setters


    public List<String> getLogs() {
        return logs;
    }

    public void setLogs(List<String> logs) {
        this.logs = logs;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getDeployable() {
        return deployable;
    }

    public void setDeployable(Boolean deployable) {
        this.deployable = deployable;
    }
}
