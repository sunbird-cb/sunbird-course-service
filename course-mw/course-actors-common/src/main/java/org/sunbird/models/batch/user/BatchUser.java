package org.sunbird.models.batch.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.sql.Timestamp;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchUser implements Serializable {

    private static final long serialVersionUID = 1L;
    private String batchId;

    private String userId;

    private Boolean active;

    private Timestamp enrolledDate;

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Timestamp getEnrolledDate() {
        return enrolledDate;
    }

    public void setEnrolledDate(Timestamp enrolledDate) {
        this.enrolledDate = enrolledDate;
    }
    
}