/*
    Copyright 2016-2019 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.universalgcodesender.listeners;

import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;

import java.util.Optional;

/**
 * A builder for creating controller status
 *
 * @author Joacim Breiler
 */
public class ControllerStatusBuilder {
    private String stateString = "";
    private ControllerState state = ControllerState.UNKNOWN;
    private Position machineCoord = null;
    private Position workCoord = null;
    private Double feedSpeed = 0d;
    private UnitUtils.Units feedSpeedUnits = UnitUtils.Units.MM;
    private Double spindleSpeed = 0d;
    private ControllerStatus.OverridePercents overrides = null;
    private Position workCoordinateOffset = Position.ZERO;
    private ControllerStatus.EnabledPins pins = null;
    private ControllerStatus.AccessoryStates accessoryStates = null;
    private ControllerStatus lastStatus = null;
    private UnitUtils.Units reportingUnits = null;
    private boolean overwritten = false;
    private boolean stateOverWritten = false;


    public ControllerStatusBuilder setReportingUnits(UnitUtils.Units reportingUnits) {
        this.reportingUnits = reportingUnits;
        return this;
    }


    public ControllerStatusBuilder setLastStatus(ControllerStatus lastStatus) {
        this.lastStatus = lastStatus;
        return this;
    }

    public static ControllerStatusBuilder newInstance(ControllerStatus controllerStatus) {
        ControllerStatusBuilder controllerStatusBuilder = new ControllerStatusBuilder();
        if(controllerStatus != null) {
            controllerStatusBuilder.setStateString(controllerStatus.getStateString())
                .setState(controllerStatus.getState())
                .setMachineCoord(controllerStatus.getMachineCoord())
                .setWorkCoord(controllerStatus.getWorkCoord())
                .setFeedSpeed(controllerStatus.getFeedSpeed())
                .setFeedSpeedUnits(controllerStatus.getFeedSpeedUnits())
                .setSpindleSpeed(controllerStatus.getSpindleSpeed())
                .setOverrides(controllerStatus.getOverrides())
                .setWorkCoordinateOffset(controllerStatus.getWorkCoordinateOffset())
                .setPins(controllerStatus.getEnabledPins())
                .setAccessoryStates(controllerStatus.getAccessoryStates());
        }
        return controllerStatusBuilder;
    }

    public ControllerStatusBuilder setStateString(String stateString) {
        this.stateString = stateString;
        return this;
    }

    public ControllerStatusBuilder setState(ControllerState state) {
        this.state = state;
        stateOverWritten = true;
        return this;
    }

    public ControllerStatusBuilder setMachineCoord(Position machineCoord) {
        this.machineCoord = machineCoord;
        return this;
    }

    public ControllerStatusBuilder setWorkCoord(Position workCoord) {
        this.workCoord = workCoord;
        return this;
    }

    public ControllerStatusBuilder setFeedSpeed(Double feedSpeed) {
        if (!overwritten) {
            this.feedSpeed = feedSpeed;
        }
        return this;
    }

    public ControllerStatusBuilder setFSFeedSpeed(Double feedSpeed) {
        this.feedSpeed = feedSpeed;
        this.overwritten = true;
        return this;
    }

    public ControllerStatusBuilder setFeedSpeedUnits(UnitUtils.Units feedSpeedUnits) {
        this.feedSpeedUnits = feedSpeedUnits;
        return this;
    }

    public ControllerStatusBuilder setSpindleSpeed(Double spindleSpeed) {
        this.spindleSpeed = spindleSpeed;
        return this;
    }

    public ControllerStatusBuilder setOverrides(ControllerStatus.OverridePercents overrides) {
        this.overrides = overrides;
        return this;
    }

    public ControllerStatusBuilder setWorkCoordinateOffset(Position workCoordinateOffset) {
        this.workCoordinateOffset = workCoordinateOffset;
        return this;
    }

    public ControllerStatusBuilder setPins(ControllerStatus.EnabledPins pins) {
        this.pins = pins;
        return this;
    }

    public ControllerStatusBuilder setAccessoryStates(ControllerStatus.AccessoryStates states) {
        this.accessoryStates = states;
        return this;
    }

    public static ControllerState getControllerStateFromStateString(String stateString) {
        return Optional.ofNullable(stateString)
                       .map(ControllerStatusBuilder::getControllerState)
                       .orElse(ControllerState.UNKNOWN);
    }

    private static ControllerState getControllerState(String s) {
        switch (s.toLowerCase()) {
            case "jog":
                return ControllerState.JOG;
            case "run":
                return ControllerState.RUN;
            case "hold":
                return ControllerState.HOLD;
            case "door":
                return ControllerState.DOOR;
            case "home":
                return ControllerState.HOME;
            case "idle":
                return ControllerState.IDLE;
            case "alarm":
                return ControllerState.ALARM;
            case "check":
                return ControllerState.CHECK;
            case "sleep":
                return ControllerState.SLEEP;
            default:
                return ControllerState.UNKNOWN;
        }
    }


    public ControllerStatus build() {
        if (workCoordinateOffset == null) {
            workCoordinateOffset = Optional.ofNullable(lastStatus)
                          .map(ControllerStatus::getWorkCoordinateOffset)
                          .orElseGet(() -> new Position(0, 0, 0, reportingUnits));
        }

        // Calculate missing coordinate with WCO
        if (workCoord == null && machineCoord != null) {
            setWorkCoord(machineCoord.sub(workCoordinateOffset));
        }
        if (machineCoord == null && workCoord != null) {
            setMachineCoord(workCoord.add(workCoordinateOffset));
        }

        if (lastStatus != null) {
            feedSpeed = lastStatus.getFeedSpeed();
            spindleSpeed = lastStatus.getSpindleSpeed();
        }

        if (overrides != null) {
            // If this is an override report and the 'Pn:' field wasn't sent
            // set all pins to a disabled state.
            if (pins == null) {
                pins = new ControllerStatus.EnabledPins("");
            }
            // Likewise for accessory accessoryStates.
            if (accessoryStates == null) {
                accessoryStates = new ControllerStatus.AccessoryStates("");
            }
        }
        else {
            if (lastStatus != null ) {
                overrides = lastStatus.getOverrides();
                pins = lastStatus.getEnabledPins();
                accessoryStates = lastStatus.getAccessoryStates();
            }
        }

        if (!stateOverWritten)
            state = getControllerStateFromStateString(stateString);
        return new ControllerStatus(stateString, state, machineCoord, workCoord, feedSpeed, feedSpeedUnits, spindleSpeed, overrides, workCoordinateOffset, pins, accessoryStates);
    }
}