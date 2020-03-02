/*
 * Copyright 2020 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

const PIPELINE_VAULT_PREFIX = 'PIPELINE_VAULT_';
const SECRET_SEPARATOR = '__';
const STORE = 'streamsets';
const USER_GROUP = 'all';
const SEPARATOR = '/';

// Controller for entering credentials
angular
  .module('dataCollectorApp.home')
  .controller('elCredentialConfigurationController', function (
    $scope, api
  ) {
    angular.extend($scope, {
      credentialSettings: {
        credentialValue: '',
        showCredential: false,
        showExpression: false,
        saved: false
      },

      // Save a typed in credential to the interal secret store
      saveCredential: function() {
        const credentialValue = $scope.credentialSettings.credentialValue;
        if (credentialValue.length < 1) {
          return;
        }
        let vaultName = `${PIPELINE_VAULT_PREFIX}${$scope.pipelineConfig.pipelineId}`;
        let secretName = `${$scope.selectedObject.instanceName}${SECRET_SEPARATOR}${$scope.configDefinition.name}`;
        let expression = `\${credential:get("${STORE}", "${USER_GROUP}", "${vaultName}${SEPARATOR}${secretName}")}`;
        api.secret.createOrUpdateTextSecret(
          vaultName,
          secretName,
          credentialValue)
        .then((response) => {
          $scope.detailPaneConfig.configuration[$scope.configIndex].value = expression;
          $scope.credentialSettings.credentialValue = '';
          $scope.credentialSettings.saved = true;
        }).catch((err) => {
          console.error(err);
        });
      },

      expressionValue: function(newValue) {
        if (arguments.length > 0) {
          // setter
          return $scope.detailPaneConfig.configuration[$scope.configIndex].value = newValue;
        } else {
          // getter
          const currentValue = $scope.detailPaneConfig.configuration[$scope.configIndex].value;
          // Do not return value if using internal credential store
          if (!currentValue || currentValue.startsWith(`\${credential:get("${STORE}"`)) {
            return '';
          } else {
            return $scope.detailPaneConfig.configuration[$scope.configIndex].value;
          }
        }
      },

      toggleShowExpression: function() {
        $scope.credentialSettings.showExpression = !$scope.credentialSettings.showExpression;
        $scope.credentialSettings.saved = false;
      },

      toggleShowCredential: function() {
        $scope.credentialSettings.showCredential = !$scope.credentialSettings.showCredential;
      },

      blankCredential: function() {
        $scope.credentialSettings.credentialValue = '';
      },

      onCredentialInputKeyup: function($event) {
        if($event.key === "Enter") {
          $scope.saveCredential();
        }
      }
    });

    $scope.$watch('credentialSettings.credentialValue', function(newValue, _oldValue, scope) {
      if (newValue.length > 0) {
        scope.credentialSettings.saved = false;
      }
    });
  });
