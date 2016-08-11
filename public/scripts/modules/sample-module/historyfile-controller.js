define(['angular', './sample-module'], function(angular, sampleModule,dropzone) {
		    'use strict';
		    return sampleModule.controller('HistoryFileCtrl', ['$scope','$http', function($scope,$http,$stateParams) {
		    	 var updateLatheStatus = function(){
		    	 	$http.get('/api/blob/v1/blob').success(function(data){
                    $scope.bimages=data;
              });
		    	 };

		    	 setInterval(function(){
            $scope.$apply(updateLatheStatus);
        },20000);

        updateLatheStatus();
		    }]);
		});