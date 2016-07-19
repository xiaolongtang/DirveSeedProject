		define(['angular', './sample-module'], function(angular, sampleModule) {
		    'use strict';
		    return sampleModule.controller('HistoryCtrl', ['$scope','$http', function($scope,$http,$stateParams) {
               
                var callMethod=function( i){
                     $http.get('/api/ms/VoltageHistory?number='+i).success(function(data){
						var barData = [];
		
			    		angular.forEach(data, function(obj,index,array){
							var item = [];
							item[0] = obj.time;
							item[1] = parseInt(obj.value);
							barData[index] = item;
							//console.log(barData[index]);
						});
console.log(i);

			    		switch(i)
			    		{
			    	       case 1:
		                      $scope.data1 = barData;
		                      // console.log("data1:");
		                      // console.log($scope.data1);
		                    break;
		                    case 2:
		                      $scope.data2 = barData;
		                    break;
		                    case 3:
		                      $scope.data3 = barData;
		                    break;
		                    case 4:
		                      $scope.data4 = barData;
		                    break;
		                    case 5:
		                      $scope.data5 = barData;
		                    break;
		                    case 6:
		                      $scope.data6 = barData;
		                    break;
		                    case 7:
		                      $scope.data7 = barData;
		                    break;
		                    case 8:
		                      $scope.data8 = barData;
		                    break;
		                    case 9:
		                      $scope.data9 = barData;
		                    break;
		                    default:
		                    console.log("default");
		                    break;
			    		}
			    		//$scope.data = barData;
			    		// console.log($scope.data);
			         });
                };

                
		    	var updateStatus = function(){
		    		//3
			   //  	$http.get('/api/ms/VoltageHistory?number=3').success(function(data){
			   //  		// console.log('data = ' + JSON.stringify(data));
						// var barData = [];
						// // var dataStr='[{\"time\": 1467243572644,\"value\": \"6600\"}]';
						// // var data=JSON.parse(dataStr);
			   //  		angular.forEach(data, function(obj,index,array){
						// 	var item = [];
						// 	item[0] = obj.time;
						// 	item[1] = parseInt(obj.value);
						// 	barData[index] = item;
						// 	//console.log(barData[index]);
						// });
			    		
			   //  		$scope.data = barData;
			   //  		// console.log($scope.data);
			   //       });

			    	for(var i=1;i<10;i++){
			    		
			    		callMethod(i);
			    	}

		    	};

		    	setInterval(function(){
		            $scope.$apply(updateStatus);
		        },360000);

		        updateStatus();

		    }]);
		});