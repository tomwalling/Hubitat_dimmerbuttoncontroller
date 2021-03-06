/**
 *  Thermostat Up/Down When Away
 * 
 *  Raw code import URL:  https://raw.githubusercontent.com/RMoRobert/Hubitat/master/apps/Thermostat%20Up-Down%20When%20Away/Thermostat%20Up-Down%20When%20Away.groovy
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  Last modified: 2019-02-27
 *  Changes:
 *   20190313: added "and" vs. "or" for presence and motion
 *   20190305: fixed notification message text
 *   20190227: added ability to use presence sensors (in addition to or instead of motion)
 *   20181108: bug fixes when debug logging disabled
 *   20181102: added time/mode restructions 
 *
 */
 
definition(
name: "Thermostat Up/Down When Away",
namespace: "RMoRobert",
author: "RMoRboert",
description: "Automatically turn thermostat up/down when you're not home based on motion sensors and/or presence",
category: "Convenience",
iconUrl: "",
iconX2Url: "",
iconX3Url: ""
)

preferences {
	mainPage()
}

def mainPage() {
	page(name:"mainPage", title:"Settings", install: true, uninstall: true) {
		section("Turn this thermostat up/down") {
			input (name:"thermostat", type: "capability.thermostat", title: "Select thermostat", required: true, multiple: false)
		}
		section("Choose sensors") {
	   		input (name:"motions", type: "capability.motionSensor", title: "When these motion sensor(s) are inactive",  multiple: true, required: false)		
			input ("minutesToDelay", "number", title: "for this many minutes", required: false)
	   		//input (name:"contacts", type: "capability.contactSensor", title: "Select contact sensors", required: false, multiple: true)	
			input (name:"presences", type: "capability.presenceSensor", title: "When these presence sensor(s) become away", required: false, multiple: true)
			input ("boolOr", "bool", title: "Change if motion OR presence conditions met (default is \"and\")")
		}
		section("Setpoints when away") {
			input ("setpointHeat", "number", title: "Heating setpoint", required: true)
			input ("setpointCool", "number", title: "Cooling setpoint", required: true)
			//input ("boolAlwaysChange", "bool", title: "Change to configured setpoint even if above cooling setpoint or below heating setpoint")
		}
		section("Restrictions", hideable: true, hidden: false) {
			// TODO: Make dynamic?
			input "starting", "time", title: "Only between this time", required: false
        	input "ending", "time", title: "and this time", required: false
			input "onlyInModes", "mode", title: "Only during these modes", multiple: true, required: false			
		}
   
		section("Notify when changed") {
			input (name:"notifyDevices", type: "capability.notification", title: "Notify these devices", required: false, multiple: true)
		}
		
		section("Logging", hideable: true, hidden: true) {
			input ("debugLogging", "bool", title: "Enable verbose/debug logging")
		}
	}
}

def installed() {
	initialize()
}

def updated() {
	unsubscribe()
	unschedule()
	initialize()
}

def initialize() {
	log.debug "Initializing"
	state.isMotionOK = false
	subscribe(motions, "motion", motionHandler)
	subscribe(presences, "presence", presenceHandler)
}

def isModeOK() {
    logDebug "Running isModeOK()..."
    def retVal = !onlyInModes || onlyInModes.contains(location.mode)
    logDebug "Exiting isModeOK(). Return value = ${retVal}"
    return retVal
}

// Returns false if user has specified "run between" times and the current time
// is outside those times. Otherwise, returns true.
def isTimeOK() {
    logDebug "Checking if time constraints specified and time is OK..."
    def retVal = true
    if (starting && ending) {
        def currTime = new Date()
        def startTime = timeToday(starting, location.timeZone)
        def stopTime = timeToday(ending, location.timeZone)
        retVal = timeOfDayIsBetween(startTime, stopTime, currTime, location.timeZone)
    }
    logDebug "Done checking time constraints. Time OK = ${retVal}"
    return retVal
}

def motionHandler(evt) {
	if (isModeOK() && isTimeOK()) {
		def activeMotionSensors = motions?.findAll { it?.latestValue("motion") == "active" }
		if (!activeMotionSensors) {
			logDebug "No active motion sensors; setting isMotionOK to true in ${minutesToDelay ?: 0} minutes"
			runIn((minutesToDelay ?: 0) * 60, setMotionOK)
		}
		else {
			logDebug "Some motion sensors still active; setting isMotionOK to false and unscheduling any future changes"
			unschedule(setMotionOK)
			state.isMotionOK = false
		}
	}
	else {
		logDebug("Not handling motion because outside specified mode and/or time constraints (mode OK = ${isModeOK()}; time OK = ${isTimeOK()})")
	}
}

def presenceHandler(evt) {
	if (evt.value == "present") {
		logDebug("Skipping presenceHandler because presence event is an arrival")
		return
	}
	logDebug("""Entering presenceHandler. Motion OK = {state.isMotionOK}, presence OK = {isPresenceOK()}, 'or' vs. 'and' = ${boolOr ? "or" : "and" }""")
	if (isModeOK() && isTimeOK()) {
		if (isPresenceOK() && (!motions || state.isMotionOK || boolOr)) {
			log.debug("Motion and/or presence conditions met after presence change; adjusting thermostat")
			adjustThermostat()
		}
		else {
			logDebug("Motion or presence not OK, not adjusting (presence OK = ${isPresenceOK()}, motion OK = ${!motions || state.isMotionOK})")
		}
	}
	else {
		logDebug("Not handling presence because outside specified mode and/or time constraints (mode OK = ${isModeOK()}; time OK = ${isTimeOK()})")
	}
}

def isPresenceOK() {
	def presentSensors = presences?.findAll { it?.latestValue("presence") == "present" }
	if (!presentSensors) {
		logDebug("All presence sensors away")
		return true
	}
	else {
		logDebug("Some presence sensors still present")
	}
	return false
}

def setMotionOK() {
	logDebug("Setting motion to OK")
	state.isMotionOK = true
	if (isPresenceOK() || boolOr) {
		log.debug "Motion and/or presence conditions met after motion timeout; adjusting thermostat"
		adjustThermostat()
	}
}

def adjustThermostat() {
	logDebug "Adjusting thermostat..."
	def thermostatMode = thermostat.currentValue("thermostatMode")
	def changed = false
	if (thermostatMode == "off") {		
		log.debug "Not adjusting because thermostat is off"
	}
	else if (!isModeOK() || !isTimeOK()) {
		log.debug "Thermostat not adjusted because outside of specified mode or time restrictions"
	}
	else {
		def targetSetpoint = setpointHeat
		def currSetpoint = thermostat.currentValue("thermostatSetpoint")
		logDebug "Current setpoint = ${currSetpoint}"
		logDebug("Thermostat mode = ${thermostatMode}")
		// COOL MODE LOGIC
		if (thermostatMode == "cool") {
			logDebug "Thermostat in cool mode"
			targetSetpoint = setpointCool
			if (currSetpoint > targetSetpoint - 0.9 && currSetpoint < targetSetpoint + 0.9) {
				logDebug "Thermostat not changed because setpoint of ${targetSetpoint} is already close to target of ${targetSetpoint}"
				} else {
				thermostat.setCoolingSetpoint(targetSetpoint)
				changed = true
				log.debug "Set thermostat cooling setpoint to ${targetSetpoint}"				
				//Doing again because I swear sometimes it doesn't work...
				thermostat.setCoolingSetpoint(targetSetpoint)
			}			
		}
		// HEAT MODE LOGIC
		else if (thermostatMode == "heat") {
			logDebug "Thermostat in heat mode"
			if (currSetpoint > targetSetpoint - 0.9 && currSetpoint < targetSetpoint + 0.9) {
				log.debug "Thermostat not changed because setpoint of ${currSetpoint} is already close to target of ${targetSetpoint}"
			}
			else {
				thermostat.setHeatingSetpoint(targetSetpoint)
				changed = true
				log.debug "Set thermostat heating setpoint to ${targetSetpoint}"
				//Doing again because I swear sometimes it doesn't work...
				thermostat.setHeatingSetpoint(targetSetpoint)
			}
		}
		// OTHER
		else {
			log.warn "Not adjusting because unable to handle thermostatMode: ${thermostat.thermostatMode}"
		}
		if (changed) {
			def strDirection = "up"
			if (currSetpoint > targetSetpoint) strDirection = "down"
			if (notifyDevices) notifyDevices.deviceNotification("Thermostat turned ${strDirection} to ${targetSetpoint} because of inactivity or non-presence")
		}
		logDebug "Changed = ${changed}"
		logDebug "Finished thermostat adjustment"
	}
}

def logDebug(msg) {
	if (debugLogging) log.debug(msg)
}
