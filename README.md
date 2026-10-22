# OfflineChat Mesh v2

The Android Mesh v2 upgrade is on the **mesh-v2-wip** branch, under
[`offlinechat`](offlinechat). It is experimental; physical Bluetooth relay
tests and AndroidKeyStore device tests are still required.

- [Download Android Mesh 2.0.0 APK](https://github.com/MADEEVESH/github_test/raw/refs/heads/mesh-v2-wip/builds/OfflineChat-Mesh-2.0.0.apk)
- [Download Mesh 2.0.0 source ZIP](https://github.com/MADEEVESH/github_test/raw/refs/heads/mesh-v2-wip/builds/OfflineChat-Mesh-2.0.0-source.zip)
- [Setup, architecture and test instructions](offlinechat/README.md)
- [Executed build results](offlinechat/BUILD_REPORT.md)
- [Windows v0.2 download](https://github.com/MADEEVESH/github_test/raw/refs/heads/offlinechat-build/builds/OfflineChat-Windows-x64.zip) — retained legacy direct chat; it does not join the Android mesh.

The original repository content follows.

---

# github_test
#include<iostream>
#include<cmath>

using namespace std;

int input_matrix(int matrix[][4],int n1)
{
	for(int i=0;i<=n1;i++)
	{
    	for(int j = 0;j<=4;j++)
    	{
         	cout<<"Enter value to enter in the matrix:";
         	cin>>matrix[i][j];

    	}
	}

   return matrix[n1][4];

}


void print_matrix(int matrix[][4],int rows)
{
	for(int i = 0;i<=rows;i++)
	{
    	for(int j = 0;j<=4;j++)
    	{
        	cout<<matrix[i][j]<<" ";
    	}
    	cout<<endl;
	}
}



void sum_matrix(int matrix1[][4],int matrix2[][4],int no_of_row)
{
	int summatrix[no_of_row][4];

	for(int i=0;i<=no_of_row;i++)
	{
    	for(int j=0;j<=4;j++)
    	{
        	summatrix[i][j] = matrix1[i][j] + matrix2[i][j];
    	}
	}

  	print_matrix(summatrix,no_of_row);
}






int main()
{
	int n;

	cout<<"Enter n:";
	cin>>n;

	int matrix_A[n][4];
	int matrix_B[n][4];
	int sum_of_matrix_AB[n][4];

	matrix_A[n][4] = input_matrix(matrix_A,n);

	cout<<"Enter values for second matrix"<<endl;

	matrix_B[n][4] = input_matrix(matrix_B,n);


	sum_matrix(matrix_A,matrix_B,n);

	return 0;
}
