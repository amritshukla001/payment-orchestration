package com.payflow.fraudservice.rules;

import com.payflow.common.commands.CheckFraudCommand;
import org.springframework.stereotype.Component;
import java.util.Optional;

@Component
public class PositiveAmountRule implements FraudRule {

    @Override
    public Optional<String> checkForRejection(CheckFraudCommand command) {
        if (command.amountCents() <= 0) {
            return Optional.of("Non-positive amount");
        }
        return Optional.empty();
    }
}
