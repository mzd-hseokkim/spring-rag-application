package kr.co.mz.ragservice.dashboard;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "model_pricing")
public class ModelPricingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "model_name", nullable = false, unique = true, length = 200)
    private String modelName;

    @Column(name = "input_price_per_1m", nullable = false, precision = 10, scale = 4)
    private BigDecimal inputPricePer1m = BigDecimal.ZERO;

    @Column(name = "output_price_per_1m", nullable = false, precision = 10, scale = 4)
    private BigDecimal outputPricePer1m = BigDecimal.ZERO;

    @Column(nullable = false, length = 10)
    private String currency = "USD";

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ModelPricingEntity() {}

    public ModelPricingEntity(String modelName, BigDecimal inputPricePer1m, BigDecimal outputPricePer1m, String currency) {
        this.modelName = modelName;
        this.inputPricePer1m = inputPricePer1m;
        this.outputPricePer1m = outputPricePer1m;
        this.currency = currency;
    }

    @PrePersist
    void prePersist() { this.updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void preUpdate() { this.updatedAt = LocalDateTime.now(); }

    public UUID getId() { return id; }
    public String getModelName() { return modelName; }
    public BigDecimal getInputPricePer1m() { return inputPricePer1m; }
    public BigDecimal getOutputPricePer1m() { return outputPricePer1m; }
    public String getCurrency() { return currency; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setInputPricePer1m(BigDecimal inputPricePer1m) { this.inputPricePer1m = inputPricePer1m; }
    public void setOutputPricePer1m(BigDecimal outputPricePer1m) { this.outputPricePer1m = outputPricePer1m; }
    public void setCurrency(String currency) { this.currency = currency; }
}
