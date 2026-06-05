package com.weekendplanner.engine.routing;

import com.weekendplanner.engine.understanding.TurnUnderstandingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class InitialRequestRouter {

    private final InitialTurnRouter initialTurnRouter;

    public InitialRequestRouter() {
        this(new InitialTurnRouter(TurnUnderstandingService.fallbackOnly()));
    }

    @Autowired
    public InitialRequestRouter(InitialTurnRouter initialTurnRouter) {
        this.initialTurnRouter = initialTurnRouter == null
                ? new InitialTurnRouter(TurnUnderstandingService.fallbackOnly())
                : initialTurnRouter;
    }

    public InitialRouteCommand route(String prompt) {
        return initialTurnRouter.route(prompt);
    }

    public InitialRouteCommand route(String prompt, String source, String structuredMarker) {
        return initialTurnRouter.route(prompt, source, structuredMarker);
    }

    public IntentEvidence evidence(String prompt) {
        return initialTurnRouter.evidence(prompt);
    }

    public boolean isCompleteStructuredPlanRequest(String prompt) {
        InitialRouteCommand command = route(prompt);
        return command.mode() == InitialRouteMode.CREATE_PLAN;
    }
}
